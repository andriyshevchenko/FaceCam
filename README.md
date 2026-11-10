# FaceCam

Android-програма для блогерів: записує фронтальну камеру у фоні, поки ви в Instagram, TikTok чи читаєте сценарій. Прев'ю камери не перекриває екран — лише сповіщення з кнопкою «Стоп».

**Сайт:** лендінг, [Політика конфіденційності](site/privacy.html) і [Умови використання](site/terms.html) лежать у папці `site/` і деплояться на Vercel.

## Можливості

- запис фронтальної або основної камери у фоні та при заблокованому екрані;
- 1080p / 720p, звук можна вимкнути;
- затримка старту (3/5/10 с) і автостоп (1–60 хв), лічильник часу в сповіщенні;
- список записів з переглядом, «Поділитися» і видаленням; після запису — сповіщення з кнопкою «Поділитися»;
- діалог згоди з Умовами та Політикою при першому запуску;
- без інтернет-дозволу, аналітики й реклами — відео лише в галереї (`Movies/FaceCam`).

## Дві версії

| Flavor | Пакет | Для чого |
|---|---|---|
| `play` | `ua.facecam` | Google Play. Сповіщення під час запису видно завжди. |
| `personal` | `ua.facecam.personal` | Особиста APK. Додатково є режим прихованого сповіщення. Не публікується в релізах. |

## Збирання

GitHub Actions (`.github/workflows/build.yml`) збирає на кожен пуш у `main`:

- `FaceCam.apk` — Play-версія для встановлення;
- `FaceCam-Personal.apk` — особиста версія;
- `FaceCam.aab` — бандл для завантаження в Google Play Console.

Файли — в Actions → потрібний запуск → Artifacts. На тег `v*` створюється GitHub Release з `FaceCam.apk` і `FaceCam.aab` (саме на нього посилається кнопка «Завантажити» на сайті).

### Підпис

Ключ завантаження (upload key) лежить у репозиторії зашифрованим: `signing/upload.jks.enc` (AES-256, PBKDF2). Пароль — у секреті репозиторію **`FACECAM_SIGNING_KEY`** (Settings → Secrets and variables → Actions). Без секрету збірки на `main` підписуються debug-ключем, а релізи за тегом не створюються.

Локально:

```bash
export FACECAM_KEY_PASSWORD='<пароль>'
openssl enc -d -aes-256-cbc -pbkdf2 -iter 200000 -in signing/upload.jks.enc -out /tmp/upload.jks -pass env:FACECAM_KEY_PASSWORD
export FACECAM_KEYSTORE=/tmp/upload.jks
./gradlew assemblePlayRelease bundlePlayRelease
```

Потрібні JDK 17+ і Android SDK (platform 36). minSdk 29 (Android 10), targetSdk 36.

## Публікація в Google Play — чекліст

- [ ] Увімкнути Play App Signing і завантажити `FaceCam.aab`.
- [ ] Privacy Policy URL: `<сайт>/privacy`.
- [ ] Data safety: дані не збираються й не передаються.
- [ ] App content → Foreground service permissions: тип **camera** (і microphone), сценарій «Background camera streaming / recording», відео: користувач натискає «Почати запис», переходить в іншу програму, видно сповіщення з кнопкою «Стоп».
- [ ] Новий особистий акаунт: закритий тест з 12 тестувальниками протягом 14 днів.
