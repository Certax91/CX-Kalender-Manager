# CX Kalender Manager

Android-Kalender, der alles an einem Ort zeigt:

- **Google-Kalender** und **Samsung-Kalender** (alle Kalender, die auf dem Handy synchronisiert sind)
- **Geburtstage aus den Google-Kontakten**
- **Samsung Aufgaben** (aus dem Samsung Kalender)

Ansichten: Monat (wischen zum Blättern), Liste der nächsten 120 Tage, Aufgaben.
In den Einstellungen lässt sich jeder Kalender einzeln ein- und ausblenden.
Die App liest nur – Termine werden zum Bearbeiten in der Kalender-App geöffnet.

## Installieren

1. Neueste Version: **Releases → Latest → `CX-Kalender-Manager.apk`** herunterladen.
2. APK öffnen, „Aus dieser Quelle installieren“ erlauben.
3. Beim ersten Start Zugriff auf **Kalender** und **Kontakte** erlauben.

Jeder Push auf `main` baut automatisch eine neue APK (GitHub Actions → „APK bauen“).
Neue Versionen lassen sich direkt über die alte installieren (fester Signaturschlüssel).

## Hinweise

- Samsung Aufgaben werden aus einer nicht offiziell dokumentierten Tabelle des Samsung-Kalenders gelesen.
  Unter *Einstellungen → Samsung Aufgaben* steht, ob und wie viele gefunden wurden.
  Aufgaben aus der separaten App **„Samsung Reminder / Erinnerungen“** sind für andere Apps nicht lesbar.
- Der Google-Geburtstagskalender ist standardmäßig ausgeblendet, damit Geburtstage nicht doppelt erscheinen.
