# 漫译 · MangaGlass

[简体中文](README.md) | **English**

MangaGlass is an Android screenshot translation tool for manga, games, and other on-screen content. It uses a vision model to recognize source text, translate it into Simplified Chinese, and overlay the translation at the corresponding positions.

[Download the APK](https://github.com/DS-BigMountain/MangaGlass/releases/latest)

## Features

- Automatically detects source languages, including multiple languages in the same image.
- Translates the current screen through a floating button, with controls to preview the original image, enlarge translations with a long press, or close the overlay.
- Wraps text based on the source font size and reduces the font size until the complete translation fits its region. Translations with overlapping or undetermined positions appear at the bottom of the screen.
- Supports custom API services and vision models, model listing, and image translation capability checks.
- Provides a Quick Settings tile, daily token usage statistics, and duration records for the 30 most recent translations.

## Requirements

Android 8.0 or later, an internet connection, and an API key for an HTTPS service compatible with Chat Completions that supports image input. Display-over-other-apps and screen capture permissions are required.

## Usage

1. Open API settings (「API 设置」), select a provider, enter an API key and a model that supports images, then validate and save the settings. Leave the API base URL empty to use the selected provider's default URL; custom services require a URL.
2. Tap Enable floating button (「开启悬浮球」) and grant the requested permissions. Select the entire screen as the capture area.
3. Open a manga reader or game, then tap the 「译」 floating button to translate the current screen.
4. Tap outside the floating button to switch between the original image and the translation. Long-press a translation to enlarge it.
5. Tap 「×」 to close the overlay before turning the page. Long-press the floating button for 0.8 seconds to stop the service.

The underlying screen remains locked during translation and preview. Returning to the home screen, opening recent apps, or locking the device closes the current overlay. If the floating button disappears on HyperOS, check background pop-up permissions and battery restrictions.

The full screenshot is sent to the configured API provider, which may incur charges. The application does not save screenshots, source text, or translations. API keys are stored locally with encryption. Recognition and positioning accuracy depend on the model, and system-protected content may not be captured.

## Build

Building from source requires JDK 17 or 21, Android SDK Platform 35, and Build Tools 35.0.0. Set `sdk.dir` in `local.properties`, then run:

```powershell
.\scripts\build.ps1
```

The generated `app/build/outputs/apk/release/app-release-unsigned.apk` must be signed before installation.
