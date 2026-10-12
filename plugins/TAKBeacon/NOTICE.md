# Notice

TAK Beacon ATAK plugin
Copyright 2026 TAK-Solutions, LLC

Licensed under the Apache License, Version 2.0 (see LICENSE).

## Files from the ATAK SDK plugin template

This plugin was scaffolded from the TAK Product Center's `plugintemplate`
sample. These files came from it, in whole or in part, and remain the TAK
Product Center's under the TAK Software License Agreement; the Apache-2.0
license covers TAK-Solutions' changes to them only:

- `build.gradle`, `settings.gradle`, `gradle.properties`, `app/build.gradle`
- `gradle/typst.gradle`, `gradle/versions.gradle`, `gradle/wrapper/*`,
  `gradlew`, `gradlew.bat`
- `template.local.properties`
- `app/proguard-gradle.txt` (the section above "rules specific to your plugin")
- `app/src/main/AndroidManifest.xml` (the `com.atakmap.app.component`
  discovery activity and plugin-api metadata)
- `app/src/main/assets/plugin.xml`
- `app/src/main/res/drawable/btn_gray.xml`, `new_dark_button_bg.xml`,
  `new_dark_button_disabled.xml`, `new_dark_button_selected.xml`
- `app/src/main/res/values/styles.xml`, `colors.xml`, `dimen.xml`
- `app/src/main/res/xml/com_android_vending_archive_opt_out.xml`
- `docs/user_manual/formatting.typ`, `docs/user_manual/titlepage.svg`

`docs/user_manual/fonts/` carries Libre Franklin and Latin Modern Sans under
their own licenses (SIL Open Font License and the GUST Font License).

Everything under `app/src/main/java/com/taksolutions/` and
`docs/user_manual/usermanual.typ` was written for TAK Beacon.

## Embedded application

`app/src/main/assets/takbeacon.apk` is the TAK Beacon app
(com.taksolutions.takbeacon), Copyright 2026 TAK-Solutions, LLC, Apache-2.0,
as signed by Google Play. It is installed by the user through Android's
package installer and is not loaded by this plugin.
