# LaceLink Offline

Eine eigenständige, inoffizielle Android-App zur lokalen Steuerung von **Adapt BB / BB 2.0** über Bluetooth Low Energy. Sie benötigt weder Nike-Konto noch Internetverbindung.

> Status: experimenteller erster Hardware-Build. Der Quellcode und das Paketformat sind statisch getestet; ein echter Schuh-Hardwaretest steht noch aus. Beim ersten Test bitte den Fuß aus dem Schuh nehmen.

## Funktionen

- bis zu zwei Schuhe gleichzeitig scannen und verbinden
- Android-Bluetooth-Kopplung mit klarer Statusanzeige
- lokaler CoreRF-Schlüsselaustausch (MODP-DH, Challenge/Response)
- Schlüssel nur im privaten Android-App-Speicher des Geräts verwaltet
- Akkustand über Standard-BLE und proprietäre Akkunachricht
- kurze Schritte enger/weiter, Motorstopp und Zielposition
- Farbe der Schuhbeleuchtung ändern
- lokales Diagnoseprotokoll zum Kopieren
- kein `INTERNET`-Recht, keine Analyse- oder Cloud-Dienste

## Installation

1. Öffne in diesem Repository **Actions** und anschließend den neuesten erfolgreichen Lauf **Android build**.
2. Lade das Artefakt `LaceLink-debug-apk` herunter und entpacke es.
3. Installiere `app-debug.apk` auf dem Android-Handy. Android muss die Installation aus dieser Quelle erlauben.

Alternativ lässt sich das Projekt mit Android Studio (JDK 17, Android SDK 35) öffnen und als Debug-APK bauen.

## Schuhe koppeln

1. Erlaube **Geräte in der Nähe** und schalte Bluetooth ein.
2. Wecke beide Schuhe auf und tippe **Schuhe suchen**.
3. Verbinde den ersten Schuh und bestätige den Android-Systemdialog. Dabei eine Taste am Schuh gedrückt halten.
4. Wenn **APP-KOPPLUNG** erscheint, halte erneut eine Schuhtaste und tippe **Schlüssel koppeln**.
5. Wiederhole die Schritte für den zweiten Schuh. Steuerungen werden erst bei Status **BEREIT** aktiv.

Wenn ein Schuh aus einer alten Kopplung hängen bleibt, entferne **beide** Schuhe zuerst in den Android-Bluetooth-Einstellungen, schließe die frühere Adapt-App vollständig und beginne neu. Zu häufiges Scannen kann Android vorübergehend drosseln; dann etwa 30 Sekunden warten.

## Technischer Aufbau

Die App ist eine Clean-Room-Neuentwicklung. Sie verwendet ausschließlich selbst geschriebenen Code und öffentlich beobachtbares BLE-Verhalten der eigenen Hardware:

- proprietärer CoreRF-GATT-Dienst mit getrenntem Schreib- und Benachrichtigungsmerkmal
- 20-Byte-Transportsegmente mit Sequenznummer und Flusskontrolle
- kleine protobuf-kompatible Nutzlasten ohne externe Laufzeitbibliothek
- Standard-Java-Kryptografie für den vom Schuh verlangten Schlüsselaustausch

Es sind absichtlich keine Firmware-, Diagnose- oder Werksreset-Befehle implementiert.

## Datenschutz und Hinweis

Die App arbeitet offline und fordert keine Internetberechtigung an. BLE-Adressen und App-Schlüssel verlassen das Telefon nicht. **LaceLink Offline ist nicht mit Nike, Inc. verbunden oder von Nike unterstützt.** Namen von Produkten werden nur zur Kompatibilitätsbeschreibung verwendet. Es wurden weder Nike-Quellcode noch Nike-Grafiken übernommen.

## Entwicklung

```bash
./gradlew testDebugUnitTest assembleDebug
```

Die CI verwendet Gradle 8.7, Android Gradle Plugin 8.6.1 und JDK 17.
