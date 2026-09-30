package com.vishucraft.web

import org.teavm.jso.JSBody
import org.teavm.jso.JSByRef
import org.teavm.jso.JSFunctor
import org.teavm.jso.JSObject

// Calls into platform.js (the VC object).

@JSBody(script = "return performance.now();") external fun now(): Double
@JSBody(script = "return VC.width();") external fun canvasWidth(): Int
@JSBody(script = "return VC.height();") external fun canvasHeight(): Int
@JSBody(script = "return VC.scale;") external fun pixelRatio(): Double
@JSBody(params = ["f"], script = "VC.loop(f);") external fun loop(f: FrameCallback)
@JSBody(params = ["f"], script = "VC.onHide = f;") external fun onHide(f: Callback)
@JSBody(params = ["s"], script = "console.log(s);") external fun log(s: String)

@JSFunctor fun interface FrameCallback : JSObject { fun frame(t: Double) }
@JSFunctor fun interface Callback : JSObject { fun call() }

// Input events
@JSBody(script = "return VC.evCount();") external fun evCount(): Int
@JSBody(params = ["i"], script = "return VC.evKind(i);") external fun evKind(i: Int): Int
@JSBody(params = ["i"], script = "return VC.evId(i) | 0;") external fun evId(i: Int): Int
@JSBody(params = ["i"], script = "return VC.evText(i);") external fun evText(i: Int): String
@JSBody(params = ["i"], script = "return VC.evX(i);") external fun evX(i: Int): Double
@JSBody(params = ["i"], script = "return VC.evY(i);") external fun evY(i: Int): Double
@JSBody(script = "VC.evClear();") external fun evClear()
@JSBody(script = "return VC.locked();") external fun pointerLocked(): Boolean
@JSBody(params = ["on"], script = "VC.lock(on);") external fun wantPointerLock(on: Boolean)
@JSBody(script = "return VC.isTouch();") external fun isTouch(): Boolean
@JSBody(params = ["list"], script = "VC.setFields(list);") external fun setFields(@JSByRef list: FloatArray)
@JSBody(params = ["on"], script = "VC.focusText(on);") external fun focusText(on: Boolean)

// Fonts
@JSBody(params = ["chars", "cell"], script = "VC.fontSheet(chars, cell);") external fun fontSheet(chars: String, cell: Int)
@JSBody(script = "return VC.font.w;") external fun fontW(): Int
@JSBody(script = "return VC.font.h;") external fun fontH(): Int
@JSBody(params = ["i"], script = "return VC.font.xs[i];") external fun fontX(i: Int): Int
@JSBody(params = ["i"], script = "return VC.font.ys[i];") external fun fontY(i: Int): Int
@JSBody(params = ["i"], script = "return VC.font.widths[i];") external fun fontWidth(i: Int): Double
@JSBody(params = ["out"], script = "VC.fontFill(out);") external fun fontFill(@JSByRef out: IntArray)

// Sound
@JSBody(script = "return VC.audioReady();") external fun audioReady(): Boolean
@JSBody(params = ["name", "pcm", "rate"], script = "VC.addSound(name, pcm.slice(), rate);") external fun addSound(name: String, @JSByRef pcm: ShortArray, rate: Int)
@JSBody(params = ["name", "gain", "pan", "pitch"], script = "VC.play(name, gain, pan, pitch);") external fun playSound(name: String, gain: Float, pan: Float, pitch: Float)
@JSBody(params = ["v"], script = "VC.setRain(v);") external fun jsSetRain(v: Float)
@JSBody(script = "return VC.musicQueued();") external fun musicQueued(): Double
@JSBody(params = ["s", "n", "rate", "gain"], script = "VC.queueMusic(s, n, rate, gain);") external fun queueMusic(@JSByRef s: FloatArray, n: Int, rate: Int, gain: Float)

// Storage
@JSBody(script = "return VC.storedCount();") external fun storedCount(): Int
@JSBody(params = ["i"], script = "return VC.storedPath(i);") external fun storedPath(i: Int): String
@JSBody(params = ["i"], script = "return VC.storedSize(i);") external fun storedSize(i: Int): Int
@JSBody(params = ["i", "out"], script = "VC.storedFill(i, out);") external fun storedFill(i: Int, @JSByRef out: ByteArray)
@JSBody(script = "VC.storedDone();") external fun storedDone()
@JSBody(params = ["path", "bytes"], script = "VC.store(path, bytes);") external fun store(path: String, @JSByRef bytes: ByteArray)
@JSBody(params = ["path"], script = "VC.unstore(path);") external fun unstore(path: String)
@JSBody(params = ["k"], script = "return VC.pref(k);") external fun pref(k: String): String
@JSBody(params = ["k", "v"], script = "VC.setPref(k, v);") external fun setPref(k: String, v: String)
@JSBody(script = "return window.DV_VERSION || 'dev';") external fun buildVersion(): String
@JSBody(script = "var m = /[?&]look=([-0-9.]+)/.exec(location.search); return m ? m[1] : null;") external fun lookTestRaw(): String?
fun lookTest(): Float? = lookTestRaw()?.toFloatOrNull()
