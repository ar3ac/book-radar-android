# Book Radar Android 📚📱

**App e Widget nativo per Android** per scoprire e sfogliare comodamente dalla home screen i migliori libri e le novità in libreria, con copertine ad alta risoluzione, rotazione dinamica, sinossi completa e collegamenti rapidi a **Goodreads** ("Want to Read") e **Amazon**.

Porting ufficiale per Android della desklet Cinnamon *book-radar*.

---

## 🌟 Caratteristiche Principali

- **Widget Nativo per la Home Screen (4x2 / 4x3)**:
  - Design scuro traslucido (*Dark Glass*) con angoli arrotondati, ispirato all'estetica desktop.
  - Copertina del libro ad alta definizione con angoli smussati.
  - Badge della sorgente (es. `✨ Novità`) e prezzo di copertina in evidenza.
  - Titolo e autore formattati e ripuliti.
  - **Barra dei controlli interattiva sul widget**:
    - ◀ **Precedente**: sfoglia il libro precedente in memoria.
    - 📚 **Goodreads**: apre direttamente la scheda del libro su Goodreads con la ricerca ottimizzata `Titolo + Autore` per aggiungere il libro a *"Want to Read"* in 1 clic.
    - 🛒 **Store (Amazon / Giunti)**: apre la pagina d'acquisto del libro nello store o nell'app Amazon.
    - 🔄 **Sincronizza**: avvia un aggiornamento immediato del feed in background.
    - ▶ **Successivo**: passa al libro successivo.
  - **Rotazione Automatica dello Slideshow (Auto-Cycle)**:
    - Cicla automaticamente al libro successivo ogni 30 secondi quando lo schermo del telefono è attivo (`PowerManager.isInteractive`).
    - Quando lo schermo viene spento o il telefono va in standby, il ciclo si sospende automaticamente per azzerare il consumo di batteria, riprendendo non appena si riattiva il dispositivo.
  - Tocco sulla copertina o sul titolo per aprire i dettagli completi e la sinossi nell'app.

- **Sincronizzazione in Background Automatica (WorkManager)**:
  - `BookSyncWorker` pianificato periodicamente ogni 4 ore con vincolo di connettività di rete.
  - Cache locale dei metadati in JSON (`books_cache.json`) e download locale delle copertine nella memoria interna (`covers/`) per un funzionamento fluido anche completamente offline.

- **Algoritmo di Ricerca Intelligente Goodreads**:
  - Pulisce automaticamente i titoli da dizioni editoriali (es. *"Ediz. italiana"*, *"Ediz. a colori"*), fascette promozionali, numeri di volume e sottotitoli prolissi.
  - Genera query `Titolo Pulito + Autore` garantendo il matching al primo colpo senza mai incappare in schermate a "0 risultati".

- **Applicazione Companion (MainActivity)**:
  - Visualizzatore completo a carosello con copertina grande, dettagli commerciali e sinossi/trama estesa.
  - Pulsante **"Aggiungi Widget alla Schermata Home"** con supporto a `requestPinAppWidget` su Android 8.0+.
  - Pulsante per forzare la sincronizzazione manuale con indicatore di caricamento.

---

## 🛠️ Architettura e Stack Tecnologico

- **Linguaggio**: Kotlin 1.9
- **Target SDK**: Android 14+ (API 34), compatibile a ritroso fino ad Android 8.0 (API 26, minSdk)
- **Widget**: `AppWidgetProvider` + `RemoteViews` con `WidgetReceiver` per interazioni istantanee a zero latenza.
- **Background Tasks**: Android Jetpack `WorkManager`.
- **Rete & Parsing**: `OkHttp` + `Kotlinx Serialization (JSON)`.
- **Caricamento Immagini**: `Coil`.

---

## 🚀 Compilazione e Installazione Rapida

Nel repository è presente lo script [`build.sh`](file:///home/ar3ac/projects/book-radar-android/build.sh) configurato per la toolchain Android locale (`jdk-17`, `gradle-8.5` e Android SDK):

```bash
cd ~/projects/book-radar-android
./build.sh
```

Lo script compila l'APK di debug e lo copia automaticamente in `/home/Dropbox/book-radar-debug.apk`, pronto per essere installato sul telefono!

---

## 📄 Licenza

Rilasciato sotto licenza MIT.
