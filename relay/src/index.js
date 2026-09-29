// DhruvVishu online relay.
//
// Each room code is one Durable Object. The host connects to /room/<CODE>?role=host, friends to
// /room/<CODE>?role=guest. Nothing is stored: bytes from a guest go to the host with the guest's number in
// front (4 bytes, big-endian), and bytes from the host go to the guest whose number is in front.
// Text messages tell the host when a guest arrives ({"t":"open","g":n}) or leaves ({"t":"close","g":n}).

const CODE = /^[A-Z0-9]{4,8}$/;

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (url.pathname === "/" || url.pathname === "/health") {
      return new Response("DhruvVishu relay is running\n", { headers: { "content-type": "text/plain" } });
    }
    const m = url.pathname.match(/^\/room\/([A-Za-z0-9]+)$/);
    if (!m) return new Response("not found", { status: 404 });
    const code = m[1].toUpperCase();
    if (!CODE.test(code)) return new Response("bad room code", { status: 400 });
    if (request.headers.get("Upgrade") !== "websocket") return new Response("expected a websocket", { status: 426 });
    const room = env.ROOMS.get(env.ROOMS.idFromName(code));
    return room.fetch(request);
  },
};

export class Room {
  constructor(ctx, env) {
    this.ctx = ctx;
    // Keep-alive pings are answered without waking the room up.
    ctx.setWebSocketAutoResponse(new WebSocketRequestResponsePair("ping", "pong"));
  }

  host() {
    return this.ctx.getWebSockets("host")[0];
  }

  guest(n) {
    return this.ctx.getWebSockets("g" + n)[0];
  }

  async fetch(request) {
    const role = new URL(request.url).searchParams.get("role");
    if (role === "host") {
      if (this.host()) return new Response("room code in use", { status: 409 });
    } else if (role === "guest") {
      if (!this.host()) return new Response("no game with that code", { status: 404 });
      if (this.ctx.getWebSockets("guest").length >= 16) return new Response("room full", { status: 403 });
    } else {
      return new Response("role must be host or guest", { status: 400 });
    }
    const pair = new WebSocketPair();
    const [client, server] = Object.values(pair);
    if (role === "host") {
      this.ctx.acceptWebSocket(server, ["host"]);
      server.serializeAttachment({ role: "host" });
    } else {
      const n = 1 + Math.floor(Math.random() * 0x7ffffffe);
      this.ctx.acceptWebSocket(server, ["guest", "g" + n]);
      server.serializeAttachment({ role: "guest", n });
      this.host()?.send(JSON.stringify({ t: "open", g: n }));
    }
    return new Response(null, { status: 101, webSocket: client });
  }

  async webSocketMessage(ws, message) {
    const who = ws.deserializeAttachment() || {};
    if (typeof message === "string") return; // only control messages from the host, none needed yet
    const bytes = new Uint8Array(message);
    if (who.role === "guest") {
      const out = new Uint8Array(bytes.length + 4);
      new DataView(out.buffer).setUint32(0, who.n);
      out.set(bytes, 4);
      this.host()?.send(out);
    } else if (who.role === "host" && bytes.length >= 4) {
      const n = new DataView(bytes.buffer, bytes.byteOffset, 4).getUint32(0);
      this.guest(n)?.send(bytes.subarray(4));
    }
  }

  async webSocketClose(ws, code) {
    this.gone(ws);
  }

  async webSocketError(ws) {
    this.gone(ws);
  }

  gone(ws) {
    const who = ws.deserializeAttachment() || {};
    if (who.role === "host") {
      for (const g of this.ctx.getWebSockets("guest")) {
        try { g.close(1000, "the host left"); } catch (e) {}
      }
    } else if (who.role === "guest") {
      try { this.host()?.send(JSON.stringify({ t: "close", g: who.n })); } catch (e) {}
    }
    try { ws.close(1000, "bye"); } catch (e) {}
  }
}
