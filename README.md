# CX Kalender Manager

Android-Kalender, der alles an einem Ort zeigt:

- **Google-Kalender** und **Samsung-Kalender** (alle Kalender, die auf dem Handy synchronisiert sind)
- **Geburtstage aus den Google-Kontakten**
- **Samsung Aufgaben** (aus dem Samsung Kalender)

Aufbau wie der Samsung Kalender:

- **Ansichten:** Jahr, Monat, Woche, Tag, Termine (Liste), Aufgaben – umschalten über das Menü ☰
- **Blättern** durch Wischen nach links/rechts, **Heute-Knopf** oben rechts
- **Monat:** 1. Tipp wählt den Tag, 2. Tipp öffnet die Tagesansicht; Tipp auf den Titel geht eine Ebene höher
- **Woche/Tag:** Zeitraster mit aktueller Uhrzeit; Tipp auf eine freie Stunde legt dort einen Termin an
- **Neuer Termin** über den ＋-Knopf: Titel, Kalender, ganztägig, Beginn/Ende, Wiederholung, Erinnerung, Ort, Notiz
- Termine **bearbeiten und löschen** (bei Serien: nur dieser oder alle)
- In „Kalender verwalten“ lässt sich jeder Kalender einzeln ein- und ausblenden

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
