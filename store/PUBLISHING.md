# Publishing Dhruvil Craft on Google Play

Every push to the game's branch builds two things (GitHub → Actions → **Android build**):

- **DhruvVishu-apk**: the APK on the download link (TVs and phones). It updates itself.
- **DhruvVishu-google-play-aab**: the app bundle for Google Play. It has no updater, because Google Play updates
  it, and the version number goes up by itself on every build.

## One-time setup

1. **Developer account.** Sign up at play.google.com/console (one-time US$25, with ID verification). New
   personal accounts must run a **closed test with at least 12 testers for 14 days** before they can publish to
   everyone.
2. **Upload key.** On a computer with Java, run:
   `keytool -genkeypair -v -keystore play-upload.jks -keyalg RSA -keysize 2048 -validity 10000 -alias upload`
   Keep `play-upload.jks` and its password safe forever. Then turn the file into text: on Windows,
   `certutil -encode play-upload.jks key.txt` (drop the BEGIN/END lines); on Mac or Linux,
   `base64 -w0 play-upload.jks > key.txt`.
3. **GitHub secrets.** Go to Settings → Secrets and variables → Actions → New repository secret and add:
   - `PLAY_UPLOAD_KEYSTORE_BASE64`: the text in key.txt
   - `PLAY_UPLOAD_STORE_PASSWORD`: your password
   - `PLAY_UPLOAD_KEY_ALIAS`: `upload`
   - `PLAY_UPLOAD_KEY_PASSWORD`: your password

   From the next build on, the app bundle is signed with your key.
4. **Create the app** in Play Console:
   - Choose Game and Free, and keep Play App Signing on.
   - Copy the text from `listing.md`.
   - Upload the pictures in this folder and 4–8 phone screenshots.
   - Fill in Data safety and Content rating with the answers in `listing.md`.
5. **First upload, by hand.**
   - Download **DhruvVishu-google-play-aab** from the newest Actions run.
   - Go to Testing → Closed testing → Create release, upload the `.aab`, and add your testers.
   - After the 14 days, go to Production → Create release.

## Updates: automatic

After the first upload, every new version can go to Google Play by itself:

1. **Make a Google Cloud service account.**
   - In console.cloud.google.com, make a project (or use one you have) and enable the
     **Google Play Android Developer API**.
   - Go to IAM & Admin → Service accounts → Create service account. Open it, then go to Keys → Add key →
     JSON, and download the file.
2. **Invite it in Play Console.**
   - Go to Users and permissions → Invite new users and enter the service account's email
     (…@….iam.gserviceaccount.com).
   - Under App permissions, add Dhruvil Craft with **Release apps to testing tracks** and **Release to production**.
3. **Add the GitHub secret** `PLAY_SERVICE_ACCOUNT_JSON`: paste the whole JSON file.
4. **Choose where updates go (optional).**
   - Go to Settings → Secrets and variables → Actions → Variables and add `PLAY_TRACK`: `internal`
     (the default), `alpha` (closed testing), `beta` (open testing) or `production`.
   - Use `production` only after your app is allowed to publish to everyone.

From then on, each build uploads the new `.aab` to that track. Its "What's new" text is the change's
description. Updates usually pass review within hours to a day, and players' phones update themselves.

## Updates: by hand (without step 1–4)

1. Download **DhruvVishu-google-play-aab** from the newest Actions run.
2. In Play Console, go to Production (or your test track) → Create new release, upload the `.aab`, write what's
   new, then choose Review and roll out.
