# Publishing PaperFeed on Google Play

## 0. Decide the package name now

`APP_ID` in `gradle.properties` is the permanent ID on Play. After the first upload it can never change. `com.paperfeed` may already be taken, so pick something you own, such as `io.github.<you>.paperfeed` or `com.<yourname>.paperfeed`:

```properties
APP_ID=com.yourname.paperfeed
```

## 1. Developer account

1. Sign up at https://play.google.com/console. It costs a one-time **US$25**, and Google verifies your identity, which takes a few days.
2. **Personal accounts must run a closed test before going public:** at least **12 testers** opted in for **14 days in a row**. Only then can you apply for production access. Friends and colleagues with Android phones are enough; they join through an opt-in link. Organisation accounts (which need a D-U-N-S number) skip this step.

## 2. Create your upload key (once; keep it safe)

```bash
keytool -genkeypair -v -keystore paperfeed-upload.jks -alias upload \
        -keyalg RSA -keysize 4096 -validity 10000
```

Next to `settings.gradle.kts`, create `keystore.properties`. It is git-ignored, so never commit it:

```properties
storeFile=paperfeed-upload.jks
storePassword=…
keyAlias=upload
keyPassword=…
```

Back up the `.jks` file and the passwords. Play App Signing (on by default) can reset a lost *upload* key, but doing so is slow.

## 3. Build the bundle

```bash
./gradlew bundleRelease          # → app/build/outputs/bundle/release/app-release.aab
```

For every update, increase `VERSION_CODE` (and `VERSION_NAME`) in `gradle.properties`.

## 4. Play Console checklist

- **Create app**: choose a name, set default language, *App*, *Free*.
- **Store listing**:
  - short description (80 characters) and full description
  - 512×512 icon
  - 1024×500 feature graphic
  - at least 2 phone screenshots
- **Privacy policy URL** (required). Host `PRIVACY.md` publicly, for example with GitHub Pages or Google Sites, and paste the link.
- **Data safety**: PaperFeed sends no data to you; there is no analytics or ads SDK. Declare the following:
  - *No data collected* by you as the developer.
  - Network requests go to Crossref, OpenAlex, arXiv, Semantic Scholar, doi.org, publishers and Elsevier. These carry only search terms and DOIs, plus the optional contact email if the user sets one.
  - The backup goes to the **user's own** Google Drive, which counts as user-initiated transfer, not collection.
  - Purchases (tips) are handled by Google Play.
- **Ads**: No ads. **Content rating**: fill in the questionnaire; the result will be "Everyone". **Target audience**: 18+ or 13+.
- **App access**: all features work without a login.
- **Testing → Closed testing**: upload the `.aab`, add your 12+ testers, and wait 14 days. Then go to **Production → Apply**.

## 5. Google Drive backup on the Play build

Play re-signs your app with **Google's app-signing key**. Copy its SHA-1 from **Play Console → Test and release → App integrity → App signing key certificate**. Then, in Google Cloud Console → Credentials, add a second **Android OAuth client** with your `APP_ID` and that SHA-1. Keep the one for your debug/upload key as well.

On the **OAuth consent screen**, switch the publishing status to **In production** and add your privacy-policy URL. `drive.file` is a non-sensitive scope, so Google doesn't need to review the app. Without this step, only the test users you listed can connect Drive.

## 6. Donations / tips

### Google's rule

For apps distributed on Google Play, payments for digital things inside the app must go through **Google Play Billing**. Tips to the developer are treated as such a payment. Only tax-exempt charities are exempt. An external "Donate on PayPal/Ko-fi" button in the Play version risks rejection. Some regions (e.g. the US, EU, Korea) now allow external payment links, but only after you enrol in Google's specific programs.

### What the app does

- **Settings → Support development** and the **money icon** in the home top bar open the Support screen.
- In the Play build, that screen shows **Google Play tips**.
- If you set `DONATE_URL` in `gradle.properties`, a **Donate** link also appears. Use this only for builds you share outside Play, such as a GitHub release or F-Droid.

### Setting up the Play tips

1. Payments need a merchant profile: **Play Console → Settings → Payments profile**. Fill in your bank and tax details.
2. Upload at least one build that includes billing; the closed test track is fine.
3. Go to **Monetize → Products → In-app products → Create product** and create three **consumable** products:
   - `tip_small` (e.g. $1.99), name "Small tip ☕"
   - `tip_medium` (e.g. $4.99), name "Coffee & cake 🍰"
   - `tip_large` (e.g. $9.99), name "Generous tip 🎉"
4. Activate them. They appear in the app within a few hours for testers who installed it from Play. To try purchases without being charged, add your account under **Settings → License testing**.

Google keeps 15% of the first US$1M you earn each year.

## 7. Things that could get flagged in review

- **Google Scholar source**: scraping Google Scholar is against Google's terms. Consider removing `SCHOLAR` from `SourceType` in the Play build; OpenAlex search covers the same need.
- The app name must not imitate another product, and the listing must not use journal logos.
- Explain in the store listing that abstracts and metadata come from Crossref, OpenAlex, arXiv and the publishers' public feeds.
