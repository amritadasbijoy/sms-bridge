# Google Play: listing and form answers

Package `app.smsbridge`, upload `dist/sms-bridge-play-<version>.aab` (build: `./gradlew :app:bundlePlayRelease`).
Signing: Play App Signing with our own key (Option A), so website and Play versions update each other.

## Create app
- App name: **SMS Bridge**
- Default language: English (United States) – en-US
- App or game: **App** · Free or paid: **Free**

## Store listing
- **Short description (80 max):**
  Read your phone's SMS and OTP codes on your computer. End-to-end encrypted.
- **Full description:**

```
SMS Bridge shows the text messages from your Android phone in any browser on your computer, so you can read SMS and copy one-time codes (OTPs) without picking up your phone.

HOW IT WORKS
1. Install SMS Bridge on your phone.
2. On your computer, open sms.techistbd.com.
3. In the app, tap "Link a computer" and scan the QR code, or type the 8-digit code.
New messages appear on the computer the moment your phone gets them.

YOU STAY IN CONTROL
• Choose what to share: new messages only, the last 7 days or the last 30 days.
• Unlink a computer automatically after 1 day or 30 days, or keep it.
• Stop sharing with any computer with one tap.
• Delete your account and everything stored on the server from Settings.

PRIVATE BY DESIGN
• Messages are end-to-end encrypted on your phone. Our server stores them but cannot read them.
• Your password never leaves your device.
• No ads, no trackers, no analytics.

HANDY EXTRAS
• "Copy code" button for OTP messages, including codes written in words.
• SIM 1 / SIM 2 labels on dual-SIM phones.
• Several phones in one account, each with its own name.
• Optional username and password to open your messages from any browser.
• Backup and phone-to-phone transfer that stay on your devices.

SMS Bridge reads SMS only to show them on the computers you link. It does not send SMS and is not a replacement for your messaging app.
```

- App category: **Communication** · Tags: SMS, Messaging
- Contact email: **support@techistbd.com** · Website: https://sms.techistbd.com
- Privacy policy: https://sms.techistbd.com/privacy/
- Graphics: `docs/play-store/icon-512.png`, `feature-graphic.png`, `phone-1..4.png`

## App content
- **Privacy policy:** https://sms.techistbd.com/privacy/
- **App access:** All or some functionality is restricted → instructions below (no login needed).
- **Ads:** No ads.
- **Content rating:** Category "All other app types". Violence, sexuality, language, controlled substances, gambling: No. Users can interact or exchange content with each other: **No** (each user only sees their own messages). Shares user location: No. Digital purchases: No. Expected rating: Everyone / PEGI 3.
- **Target audience:** 18 and over. Not designed for children. Appeals to children: No.
- **News app:** No. **COVID-19 contact tracing:** No. **Government app:** No.
- **Financial features:** None. **Health:** None.
- **Data safety:** see below.
- **Account deletion:** In-app: Settings → Delete my account. Web URL: https://sms.techistbd.com/delete-account/
- **Sensitive permissions:**
  - SMS (READ_SMS, RECEIVE_SMS): core functionality **"SMS-based… cross-device synchronization or transfer of SMS or calls"** (permitted exception). Not the default SMS handler. Demo video required (Phase 5).
  - Foreground service type `remoteMessaging`: keeps the encrypted connection to the user's linked computers open, so new SMS reach them right away and new browsers can be approved. Started only after the user links a computer; visible notification. Video link required (Phase 5).

### Data safety answers
- Does your app collect or share any of the required user data types? **Yes**
- Is all user data encrypted in transit? **Yes**
- Do you provide a way for users to request that their data is deleted? **Yes**

| Data type | Collected | Shared | Optional? | Purposes |
|---|---|---|---|---|
| Messages → SMS or MMS | Yes (end-to-end encrypted) | No | Required | App functionality |
| Personal info → User IDs (username) | Yes | No | Required | App functionality, Account management |
| Device or other IDs (app-made device ID) | Yes | No | Required | App functionality, Security / fraud prevention |

Not collected: location, contacts, photos, files, audio, calendar, health, financial info, web history, app activity, crash logs, diagnostics, email, phone number.
Data is processed ephemerally: No. Ephemeral processing aside, messages are kept until the user removes them, deletes the account or they age out of the chosen range.

### Instructions for app access (reviewers)
```
No account or password is needed.
1. Install the app and open it. Tap "Allow" for both SMS permissions on the Home screen.
2. On a computer, open https://sms.techistbd.com in a browser. It shows a QR code and an 8-digit code.
3. In the app, tap "Link a computer" → "Scan QR code" (or "Type code" and enter the 8 digits).
4. Choose what to share and tap "Allow". The browser now shows the phone's recent SMS.
5. Send an SMS to the phone: it appears in the browser within seconds.
6. In the app, "Stop sharing" removes the browser; Settings → "Delete my account" deletes everything on the server.
```
