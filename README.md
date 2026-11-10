# Hahacam

Android-програма для блогерів: записує фронтальну камеру у фоні, поки ви в Instagram, TikTok чи читаєте сценарій. Прев'ю камери не перекриває екран — лише сповіщення з кнопкою «Стоп».

**Сайт:** https://hahacam.vercel.app — лендінг, [Політика конфіденційності](site/privacy.html) і [Умови використання](site/terms.html) лежать у папці `site/` і деплояться на Vercel.

## Можливості

- запис фронтальної або основної камери у фоні та при заблокованому екрані;
- 1080p / 720p, звук можна вимкнути;
- затримка старту (3/5/10 с) і автостоп (1–60 хв), лічильник часу в сповіщенні;
- список записів з переглядом, «Поділитися» і видаленням; після запису — сповіщення з кнопкою «Поділитися»;
- діалог згоди з Умовами та Політикою при першому запуску;
- англійська та українська мови (апка — за мовою телефона, сайт — `/` і `/en`);
- без інтернет-дозволу, аналітики й реклами — відео лише в галереї (`Movies/Hahacam`).

## Дві версії

| Flavor | Пакет | Для чого |
|---|---|---|
| `play` | `ua.hahacam` | Google Play. Сповіщення під час запису видно завжди. |
| `personal` | `ua.hahacam.personal` | Особиста APK. Додатково є режим прихованого сповіщення. Не публікується в релізах. |

## Збирання

GitHub Actions (`.github/workflows/build.yml`) збирає на кожен пуш у `main`:

- `Hahacam.apk` — Play-версія для встановлення;
- `Hahacam-Personal.apk` — особиста версія;
- `Hahacam.aab` — бандл для завантаження в Google Play Console.

Файли — в Actions → потрібний запуск → Artifacts.

**Реліз:** коли в `main` з'являється новий `versionName` (app/build.gradle) і є секрет підпису, workflow сам створює GitHub Release `v<версія>` з `Hahacam.apk` і `Hahacam.aab` — на нього посилається кнопка «Завантажити APK» на сайті. Щоб перевипустити без змін у коді: Actions → Build → Run workflow.

### Підпис

Ключ завантаження (upload key) лежить у репозиторії зашифрованим: `signing/upload.jks.enc` (AES-256, PBKDF2). Пароль — у секреті репозиторію **`HAHACAM_SIGNING_KEY`** (Settings → Secrets and variables → Actions). Без секрету збірки підписуються debug-ключем, а релізи не створюються.

Локально:

```bash
export HAHACAM_KEY_PASSWORD='<пароль>'
openssl enc -d -aes-256-cbc -pbkdf2 -iter 200000 -in signing/upload.jks.enc -out /tmp/upload.jks -pass env:HAHACAM_KEY_PASSWORD
export HAHACAM_KEYSTORE=/tmp/upload.jks
./gradlew assemblePlayRelease bundlePlayRelease
```

Потрібні JDK 17+ і Android SDK (platform 36). minSdk 29 (Android 10), targetSdk 36.

## Публікація в Google Play — чекліст

- [ ] Увімкнути Play App Signing і завантажити `Hahacam.aab`.
- [ ] Privacy Policy URL: https://hahacam.vercel.app/privacy.
- [ ] Data safety: дані не збираються й не передаються.
- [ ] App content → Foreground service permissions: тип **camera** (і microphone), сценарій «Background camera streaming / recording», відео: користувач натискає «Почати запис», переходить в іншу програму, видно сповіщення з кнопкою «Стоп».
- [ ] Новий особистий акаунт: закритий тест з 12 тестувальниками протягом 14 днів.
