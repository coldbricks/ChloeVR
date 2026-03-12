# ChloeVR — Google Play Store Publication Guide
## For coldbricks@gmail.com

---

## Step 1: Google Play Developer Account

1. Go to https://play.google.com/console/signup
2. Sign in with **coldbricks@gmail.com**
3. Pay the **one-time $25 registration fee**
4. Complete identity verification (government ID + address)
5. Wait for approval (usually 48 hours, can take up to 7 days)

**Important:** Google requires identity verification for ALL new developer accounts since 2023. Have your government ID ready.

---

## Step 2: App Signing

Google Play uses **App Signing by Google Play** — they manage the signing key.

### Generate an Upload Key
```bash
keytool -genkey -v -keystore chloe-upload.keystore \
  -alias chloe_upload -keyalg RSA -keysize 2048 -validity 10000 \
  -storepass <YOUR_PASSWORD> -keypass <YOUR_PASSWORD> \
  -dname "CN=Ash Airfoil, O=Ash Airfoil, L=New York, ST=NY, C=US"
```

### Configure in `app/build.gradle.kts`
```kotlin
android {
    signingConfigs {
        create("release") {
            storeFile = file("../chloe-upload.keystore")
            storePassword = System.getenv("KEYSTORE_PASSWORD") ?: ""
            keyAlias = "chloe_upload"
            keyPassword = System.getenv("KEY_PASSWORD") ?: ""
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true  // Enable R8/ProGuard
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }
}
```

### Build Release Bundle
```bash
cd prism
# Set env vars
export KEYSTORE_PASSWORD="your_password"
export KEY_PASSWORD="your_password"

# Build AAB (Android App Bundle — required for Play Store)
./gradlew bundleRelease

# Output: app/build/outputs/bundle/release/app-release.aab
```

---

## Step 3: ProGuard / R8 Rules

Create `prism/app/proguard-rules.pro`:
```
# Keep OpenXR JNI bridge
-keep class com.ashairfoil.prism.OpenXRInput { *; }
-keepclassmembers class com.ashairfoil.prism.OpenXRInput {
    native <methods>;
}

# Keep ExoPlayer effects
-keep class com.ashairfoil.prism.effects.** { *; }
-keep class com.ashairfoil.prism.DeoVrAlphaPackedEffect** { *; }
-keep class com.ashairfoil.prism.ChromaKeyEffect** { *; }

# Keep data classes used in JSON parsing
-keep class com.ashairfoil.prism.data.DeoVrApi$* { *; }

# Keep Kotlin coroutines
-dontwarn kotlinx.coroutines.**
-keep class kotlinx.coroutines.** { *; }

# ExoPlayer
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# Jetpack XR SDK
-keep class androidx.xr.** { *; }
-dontwarn androidx.xr.**
```

---

## Step 4: Store Listing

### App Details
- **App name:** ChloeVR
- **Short description (80 chars):** The VR video player Galaxy XR deserves. 8K, 180/360, passthrough, color grading.
- **Full description (4000 chars):**

```
ChloeVR is a powerful VR video player built natively for Samsung Galaxy XR and Android XR devices.

PLAYBACK
• VR180, VR360, and flat screen video
• Side-by-side and top-bottom 3D stereoscopic
• Fisheye projection with 10+ camera lens profiles (MKX200, VRCA220, RF52, Canon, Insta360)
• Up to 8K resolution with hardware decoding
• MP4, MKV, WebM containers
• Image viewing (JPG, PNG, WebP) in VR space

VIDEO EFFECTS
• Color grading: brightness, contrast, saturation, sharpening, gamma, hue shift
• Tone mapping presets: Reinhard, ACES Film, Cinematic, Vivid
• Lens distortion correction with camera-specific profiles
• DeoVR packed alpha passthrough
• Chroma key (green/blue screen removal) with live color picker
• IPD adjustment and stereo alignment correction

INTERACTION
• Full VR controller support (grab, rotate, zoom, roll)
• A-B repeat with loop and boomerang modes
• Speed control (0.25x to 4.0x)
• 6DOF depth simulation — subjects feel rooted in space
• Eye tracking autofocus
• Spatial audio with head-tracking stereo balance

LIBRARY
• DeoVR filename convention auto-detection
• Resume playback per file
• Favorites, ratings, and tags
• Search, filter, and sort
• DeoVR-compatible API for streaming sites
• Subtitle support (SRT, ASS/SSA)

SETTINGS
• Per-video saved adjustments (color grading, lens, IPD, screen position)
• 6 built-in color presets + unlimited custom presets
• All settings persist across sessions

Built for Samsung Galaxy XR. Also compatible with other Android XR devices.
```

### Graphics Required
- **App icon:** 512x512 PNG, no alpha
- **Feature graphic:** 1024x500 PNG (banner shown at top of listing)
- **Screenshots:** At minimum 2 screenshots. Ideally 4-8.
  - Recommended: 1920x1080 or 2560x1440
  - Show: file picker, video playing in VR, control panel, color grading, settings

### Content Rating
- Go to Play Console → Content rating → Fill out IARC questionnaire
- **IMPORTANT for VR video player:** Select that the app can be used to view user-generated content. This means the rating will be based on the app's functionality, not specific content. Most video players receive a **"Rated for 3+"** or **"Rated for 12+"** rating.
- Do NOT describe the app as specifically designed for adult content in the store listing, even though adult VR is a major use case. Keep the listing neutral — it's a general-purpose video player.

### Category
- **Category:** Video Players & Editors
- **Tags:** VR, virtual reality, video player, 360, stereoscopic, Samsung XR

---

## Step 5: Pricing & Distribution

- **Pricing model options:**
  - **Free with ads** (not recommended for VR player)
  - **Free** (build user base, monetize later via pro upgrade)
  - **Paid** ($4.99–$9.99 range, DeoVR is free, HereSphere is ~$14)
  - **Freemium** — free base player, paid unlock for advanced features (color grading, lens profiles, streaming API, presets) — **RECOMMENDED**

- **Freemium tier suggestion:**
  - Free: Basic playback, SBS/TB/mono, 180/360, basic controls
  - Pro ($7.99): Color grading, lens distortion, IPD adjust, subtitles, streaming API, presets, depth simulation, eye tracking

- **Distribution:** All countries where Google Play is available

---

## Step 6: Privacy Policy

Google requires a privacy policy URL. Create a simple one:

**Privacy Policy for ChloeVR:**
- ChloeVR does not collect, transmit, or store any personal data
- All settings and media library data are stored locally on the device
- The app does not use analytics, crash reporting, or advertising SDKs
- Eye tracking data (if used) is processed locally and never transmitted
- The streaming browser feature connects to user-specified endpoints only
- No data is shared with third parties

Host this as a GitHub Pages site or a simple text page. Add the URL to the Play Console privacy policy field.

URL: `https://coldbricks.github.io/ChloeVR/privacy` (create a `docs/` folder in the repo with an HTML privacy policy)

---

## Step 7: Release Process

### Internal Testing (do this first)
1. Play Console → Testing → Internal testing
2. Upload your AAB
3. Add tester emails (your own email + a few friends)
4. They get access via a Play Store link — install and test
5. No review process for internal testing

### Closed Testing (Alpha)
1. Create a closed testing track
2. Upload AAB, write release notes
3. Add tester list (up to 100 users)
4. Google reviews the app (1-3 days)

### Open Testing (Beta)
1. After closed testing is stable
2. Anyone can opt-in as a tester
3. Good for gathering feedback before launch

### Production Release
1. After testing is complete
2. Upload AAB to production track
3. Full Google review (1-7 days for first submission)
4. Set rollout percentage (start at 20%, increase to 100%)

---

## Step 8: Android XR Specific

### Device Targeting
In Play Console → Advanced settings → Device catalog:
- Ensure Samsung Galaxy XR is in the supported device list
- The app targets `minSdk 34` which is correct for Android XR
- The `android.hardware.xr.spatial` feature requirement will automatically filter to XR devices

### XR Listing
Google Play has special XR listing fields:
- XR screenshots (captured from the headset)
- XR feature tags
- XR-optimized badge

---

## Step 9: Monetization Setup (for paid/freemium)

1. Play Console → Monetize → Products → In-app products
2. Create a product: `chloe_pro_unlock` — One-time purchase, $7.99
3. In the app, use Google Play Billing Library to check purchase status
4. Gate pro features behind the purchase check

### Billing Library Integration (add to build.gradle.kts)
```kotlin
dependencies {
    implementation("com.android.billingclient:billing-ktx:7.1.1")
}
```

---

## Timeline

| Step | Time |
|------|------|
| Create developer account | Day 1 (48h verification) |
| Prepare store listing + graphics | Day 2-3 |
| Build release AAB + sign | Day 3 |
| Internal testing | Day 3-7 |
| Closed testing + review | Day 7-14 |
| Open testing (optional) | Day 14-21 |
| Production release | Day 21-28 |

**Fastest path:** You could be on the Play Store in 2-3 weeks if the app is stable.

---

## Files to Create

1. `prism/app/proguard-rules.pro` — ProGuard rules (above)
2. `docs/privacy.html` — Privacy policy for GitHub Pages
3. App icon — 512x512 PNG
4. Feature graphic — 1024x500 PNG
5. Screenshots — captured from the headset

---

*This guide covers the complete path from "I've never done this" to "my app is on the Play Store." Follow the steps in order. The hardest part is the initial developer account verification — everything after that is uploading files and filling out forms.*
