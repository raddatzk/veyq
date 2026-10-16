// The words of the project website (site/src/pages/index.astro and de/index.astro), one object per language. The
// website is the front door; every claim here is one the docs already make, and each section links the page that
// makes it in full. Wording follows the Use area: "Memory", "Space", "application" — in German "Erinnerung",
// "Bereich", "Anwendung", addressed with "du".

// Every language the website speaks, the default first. Adding one is adding its strings object below and a line
// here: its page (site/src/pages/[lang]/index.astro) and its entry in the language switch follow. The website links
// into the docs in the same language, so a language here must also be a Starlight locale in astro.config.mjs; a docs
// page not yet translated then falls back to English with Starlight's notice.
export const languages = [
  { code: 'en', label: 'English' },
  { code: 'de', label: 'Deutsch' },
] as const;

export type Lang = (typeof languages)[number]['code'];
export const defaultLang: Lang = languages[0].code;

// A path relative to the site base in a language: the default language at the root, every other under its code —
// `localePath('de', 'memories/')` is `de/memories/`, the same shape Starlight gives the docs.
export const localePath = (lang: Lang, path = '') => (lang === defaultLang ? path : `${lang}/${path}`);

const en = {
  htmlTitle: 'veyq — share what matters, only with the people it belongs to',
  description:
    'veyq is a self-hostable, end-to-end encrypted platform for what you share with your family, your friends or your club: photos and videos, documents and files, and more to come.',
  nav: { apps: 'Applications', how: 'How it works', run: 'Run it yourself', docs: 'Documentation', main: 'Main', language: 'Language', skip: 'Skip to content' },
  hero: {
    eyebrow: ['End-to-end encrypted', 'Self-hostable', 'Free software'],
    title: 'Share what matters — only with the people it belongs to.',
    lead:
      'veyq is a private, self-hostable platform for what you share with your family, your friends or your club: photos and videos in Memories, documents and files in Depot, and more to come. Everything is locked on your own device before it is sent, so the server that stores it cannot look inside.',
    primary: 'What veyq is',
    secondary: 'Run your own server',
    status: 'In development — there is no stable release yet.',
    shotId: 'folder',
    shot: 'One folder holding a Memory, files and a drop box side by side',
  },
  story: {
    kicker: 'Why veyq',
    title: 'Scattered across phones, inboxes and other people’s clouds',
    paragraphs: [
      'Last summer at the lake, everyone took photos. Now they are spread over five phones, a group chat that shrank them to thumbnails, and a cloud album in one person’s account. The lease, the insurance papers and the children’s certificates sit in somebody’s email inbox — each on a service that can open everything it stores.',
      'What a family, a group of friends or a club keeps together matters more in twenty years than it does today. It belongs in one place they share, in full quality, that nobody else can open — not an advertiser, not a data broker, and not whoever runs the server.',
    ],
    quote: 'That is what veyq is: you, or someone you trust, runs it. The people you invite add to it. The server only ever holds locked boxes.',
  },
  apps: {
    kicker: 'Applications',
    title: 'One platform, more than one application',
    lead:
      'What you keep in a space comes from an application. Memories and Depot are the first two: they live side by side in the same folders, and each gets the same spaces, sharing and encryption from the platform.',
    more: 'All applications',
    items: [
      {
        name: 'Memories',
        tag: 'Photos and videos',
        text: 'For the photos and videos of an occasion: a trip, a wedding, a birthday.',
        points: [
          'Chapters for each day, or each part of the day',
          'A gallery in the order the photos were taken',
          'Date, place and the people in them — all encrypted',
          'Guests add their own photos through a link',
        ],
        link: 'memories/',
        linkLabel: 'Memories in the guide',
        shot: 'gallery',
        shotShows: 'A Memory in the gallery: its chapters on top, the photos below',
      },
      {
        name: 'Depot',
        tag: 'Documents and files',
        text: 'For documents, PDFs and any other file you want to keep.',
        points: [
          'Upload single files or whole folders',
          'Open PDFs and images right in the browser',
          'Replace a file with its newer version',
          'Drop boxes collect files from people without an account',
        ],
        link: 'depot/',
        linkLabel: 'Depot in the guide',
        shot: 'depot',
        shotShows: 'A Depot folder in list view: each file with its size and when it changed',
      },
    ],
    next: {
      tag: 'On the drawing board',
      name: 'More to come',
      text: 'Because the platform already carries spaces, sharing, encryption and export, a new application only adds what is its own. Ideas being worked out: an inventory of the things you own, and the expenses you share.',
    },
  },
  how: {
    kicker: 'How it works',
    title: 'Locked before it leaves your device',
    steps: [
      {
        title: 'You add something',
        text: 'Your browser locks it — a photo or a document, its name, its date and place — with a key that only you and the people you share with hold.',
      },
      {
        title: 'The server keeps locked boxes',
        text: 'It stores them and hands them out, but holds no key that opens them. Someone who copies its database gets nothing readable.',
      },
      {
        title: 'Your people open them',
        text: 'Members of a space hold its key on their own devices. A share link carries its secret in the link itself, in a part that never reaches the server.',
      },
    ],
    seesTitle: 'What the server still sees',
    sees: 'That something exists, how big it is, who belongs to which space, and when it was uploaded or changed. Never what is inside.',
    seesLinks: [
      { label: 'Is my data safe?', path: 'privacy/is-my-data-safe/' },
      { label: 'The security model, in full', path: 'security/start-here/' },
    ],
  },
  features: {
    kicker: 'The platform',
    title: 'What every application gets',
    items: [
      { title: 'Spaces and groups', text: 'A shared space for everyone, a personal one that only you can open, and groups for smaller circles.', link: 'spaces/' },
      { title: 'Share with anyone', text: 'View only, view and add, or a drop box — with a password and an expiry if you like. Guests need no account.', link: 'sharing/' },
      { title: 'Ready for quantum computers', text: 'Keys are handed over with a classic and a post-quantum lock combined, so a copy taken today stays closed tomorrow.', link: 'privacy/quantum-computers-and-your-photos/' },
      { title: 'Take everything with you', text: 'Download any folder as a plain ZIP that unpacks into the same folders — and import it again, anywhere.', link: 'spaces/download-and-import/' },
      { title: 'Locked out, but not for good', text: 'Forgot your password? A link by email and the Secret Key from your Emergency Kit get you back in. Neither works alone.', link: 'account/forgot-password/' },
      { title: 'All your devices', text: 'Add a phone or a laptop by scanning a QR code with a device you already use.', link: 'account/adding-another-device/' },
    ],
  },
  run: {
    kicker: 'Self-hosting',
    title: 'Run it yourself',
    text:
      'Running your own server is veyq’s default, and it is also its strongest protection: on your own instance, nobody can hand you a tampered app. One signed container image, with Docker Compose or Kubernetes (Helm), a MariaDB database and an S3-compatible object store.',
    honest:
      'For the people you host, the protection holds against an operator who reads the database and the logs — not against one who changes the code you serve them. veyq says so, in the app and in the docs.',
    links: [
      { label: 'Plan an instance', path: 'run/plan/' },
      { label: 'Verify a release', path: 'run/verify-a-release/' },
      { label: 'Running your own instance', path: 'run/' },
    ],
  },
  docs: {
    kicker: 'Documentation',
    title: 'Read on',
    items: [
      { title: 'Use', who: 'For the people using veyq, and for space owners. In English and German.', path: 'start/' },
      { title: 'Run', who: 'For whoever runs a server: install, configure, back up, upgrade.', path: 'run/' },
      { title: 'Security', who: 'For researchers: the threat model, the cryptography, the known limits.', path: 'security/start-here/' },
    ],
  },
  footer: {
    license: 'veyq is free software under the GNU AGPL 3.0.',
    privacy: 'This page sets no cookies, tracks nothing and loads nothing from anyone else.',
    source: 'Source code',
    report: 'Report a vulnerability',
  },
  placeholder: 'Screenshot to come',
};

export type Strings = typeof en;

const de: Strings = {
  htmlTitle: 'veyq — teile, was zählt, nur mit den Menschen, zu denen es gehört',
  description:
    'veyq ist eine selbst betreibbare, Ende-zu-Ende-verschlüsselte Plattform für das, was du mit deiner Familie, deinen Freundinnen und Freunden oder deinem Verein teilst: Fotos und Videos, Dokumente und Dateien, und mehr kommt dazu.',
  nav: { apps: 'Anwendungen', how: 'So funktioniert’s', run: 'Selbst betreiben', docs: 'Dokumentation', main: 'Hauptnavigation', language: 'Sprache', skip: 'Zum Inhalt' },
  hero: {
    eyebrow: ['Ende-zu-Ende-verschlüsselt', 'Selbst betreibbar', 'Freie Software'],
    title: 'Teile, was zählt — nur mit den Menschen, zu denen es gehört.',
    lead:
      'veyq ist eine private, selbst betreibbare Plattform für das, was du mit deiner Familie, deinen Freundinnen und Freunden oder deinem Verein teilst: Fotos und Videos in Erinnerungen, Dokumente und Dateien im Depot, und mehr kommt dazu. Alles wird auf deinem eigenen Gerät verschlossen, bevor es verschickt wird. Der Server, der es speichert, kann deshalb nicht hineinsehen.',
    primary: 'Was veyq ist',
    secondary: 'Eigenen Server betreiben',
    status: 'In Entwicklung — es gibt noch kein stabiles Release.',
    shotId: 'folder',
    shot: 'Ein Ordner, in dem eine Erinnerung, Dateien und ein Briefkasten nebeneinander liegen',
  },
  story: {
    kicker: 'Warum veyq',
    title: 'Verstreut über Handys, Postfächer und fremde Clouds',
    paragraphs: [
      'Letzten Sommer am See haben alle fotografiert. Jetzt liegen die Bilder auf fünf Handys, in einem Gruppenchat, der sie zu Vorschaubildchen geschrumpft hat, und in einem Cloud-Album im Konto einer einzigen Person. Mietvertrag, Versicherungsunterlagen und die Zeugnisse der Kinder stecken in irgendeinem E-Mail-Postfach — jedes bei einem Dienst, der alles öffnen kann, was er speichert.',
      'Was eine Familie, ein Freundeskreis oder ein Verein gemeinsam aufbewahrt, ist in zwanzig Jahren wichtiger als heute. Es gehört an einen Ort, den sie teilen, in voller Qualität, und den niemand sonst öffnen kann — kein Werbenetzwerk, kein Datenhändler und auch nicht, wer den Server betreibt.',
    ],
    quote: 'Genau das ist veyq: Du betreibst es, oder jemand, dem du vertraust. Die Menschen, die du einlädst, tragen bei. Der Server bekommt nur verschlossene Kisten.',
  },
  apps: {
    kicker: 'Anwendungen',
    title: 'Eine Plattform, mehr als eine Anwendung',
    lead:
      'Was du in einem Bereich aufbewahrst, kommt aus einer Anwendung. Erinnerungen und Depot sind die ersten beiden: Sie liegen nebeneinander in denselben Ordnern, und jede bekommt dieselben Bereiche, dasselbe Teilen und dieselbe Verschlüsselung von der Plattform.',
    more: 'Alle Anwendungen',
    items: [
      {
        name: 'Erinnerungen',
        tag: 'Fotos und Videos',
        text: 'Für die Fotos und Videos eines Anlasses: eine Reise, eine Hochzeit, ein Geburtstag.',
        points: [
          'Kapitel für jeden Tag oder jeden Teil davon',
          'Eine Galerie in der Reihenfolge, in der fotografiert wurde',
          'Datum, Ort und die Menschen darauf — alles verschlüsselt',
          'Gäste fügen über einen Link eigene Fotos hinzu',
        ],
        link: 'memories/',
        linkLabel: 'Erinnerungen in der Anleitung',
        shot: 'gallery',
        shotShows: 'Eine Erinnerung in der Galerie: oben ihre Kapitel, darunter die Fotos',
      },
      {
        name: 'Depot',
        tag: 'Dokumente und Dateien',
        text: 'Für Dokumente, PDFs und jede andere Datei, die du aufheben willst.',
        points: [
          'Einzelne Dateien oder ganze Ordner hochladen',
          'PDFs und Bilder direkt im Browser öffnen',
          'Eine Datei durch ihre neuere Fassung ersetzen',
          'Briefkästen sammeln Dateien von Menschen ohne Konto',
        ],
        link: 'depot/',
        linkLabel: 'Das Depot in der Anleitung',
        shot: 'depot',
        shotShows: 'Ein Depot-Ordner als Liste: jede Datei mit Größe und letzter Änderung',
      },
    ],
    next: {
      tag: 'In Arbeit',
      name: 'Mehr kommt dazu',
      text: 'Weil die Plattform Bereiche, Teilen, Verschlüsselung und Export schon mitbringt, fügt eine neue Anwendung nur hinzu, was ihr eigenes ist. Ideen, die gerade ausgearbeitet werden: ein Inventar der Dinge, die dir gehören, und Ausgaben, die ihr euch teilt.',
    },
  },
  how: {
    kicker: 'So funktioniert’s',
    title: 'Verschlossen, bevor es dein Gerät verlässt',
    steps: [
      {
        title: 'Du fügst etwas hinzu',
        text: 'Dein Browser verschließt es — ein Foto oder ein Dokument, seinen Namen, Datum und Ort — mit einem Schlüssel, den nur du und die Menschen haben, mit denen du teilst.',
      },
      {
        title: 'Der Server hütet verschlossene Kisten',
        text: 'Er speichert sie und gibt sie heraus, hat aber keinen Schlüssel, der sie öffnet. Wer seine Datenbank kopiert, bekommt nichts Lesbares.',
      },
      {
        title: 'Deine Leute öffnen sie',
        text: 'Die Mitglieder eines Bereichs haben seinen Schlüssel auf ihren eigenen Geräten. Ein geteilter Link trägt sein Geheimnis in sich, in einem Teil, der nie beim Server ankommt.',
      },
    ],
    seesTitle: 'Was der Server trotzdem sieht',
    sees: 'Dass etwas existiert, wie groß es ist, wer zu welchem Bereich gehört und wann etwas hochgeladen oder geändert wurde. Nie, was drin ist.',
    seesLinks: [
      { label: 'Sind meine Daten sicher?', path: 'privacy/is-my-data-safe/' },
      { label: 'Das Sicherheitsmodell, vollständig (Englisch)', path: 'security/start-here/' },
    ],
  },
  features: {
    kicker: 'Die Plattform',
    title: 'Was jede Anwendung mitbekommt',
    items: [
      { title: 'Bereiche und Gruppen', text: 'Ein gemeinsamer Bereich für alle, ein persönlicher, den nur du öffnen kannst, und Gruppen für kleinere Kreise.', link: 'spaces/' },
      { title: 'Mit allen teilen', text: 'Nur ansehen, ansehen und hinzufügen oder ein Briefkasten — auf Wunsch mit Passwort und Ablaufdatum. Gäste brauchen kein Konto.', link: 'sharing/' },
      { title: 'Bereit für Quantencomputer', text: 'Schlüssel werden mit einem klassischen und einem Post-Quanten-Schloss zugleich übergeben. Eine Kopie von heute bleibt auch morgen zu.', link: 'privacy/quantum-computers-and-your-photos/' },
      { title: 'Alles mitnehmen', text: 'Lade jeden Ordner als einfaches ZIP herunter, das sich in dieselben Ordner entpackt — und importiere es wieder, wo du willst.', link: 'spaces/download-and-import/' },
      { title: 'Ausgesperrt, aber nicht für immer', text: 'Passwort vergessen? Ein Link per E-Mail und der Secret Key aus deinem Notfall-Kit bringen dich zurück. Keins von beiden reicht allein.', link: 'account/forgot-password/' },
      { title: 'Alle deine Geräte', text: 'Ein Handy oder einen Laptop fügst du hinzu, indem du einen QR-Code mit einem Gerät scannst, das du schon nutzt.', link: 'account/adding-another-device/' },
    ],
  },
  run: {
    kicker: 'Selbst betreiben',
    title: 'Betreib es selbst',
    text:
      'Einen eigenen Server zu betreiben ist bei veyq der Normalfall, und zugleich der stärkste Schutz: Auf deiner eigenen Instanz kann dir niemand eine manipulierte App unterschieben. Ein signiertes Container-Image, mit Docker Compose oder Kubernetes (Helm), einer MariaDB-Datenbank und einem S3-kompatiblen Objektspeicher.',
    honest:
      'Für die Menschen, die du hostest, hält der Schutz gegen einen Betreiber, der Datenbank und Logs liest — nicht gegen einen, der den ausgelieferten Code verändert. veyq sagt das offen, in der App und in der Dokumentation.',
    links: [
      { label: 'Eine Instanz planen', path: 'run/plan/' },
      { label: 'Ein Release prüfen', path: 'run/verify-a-release/' },
      { label: 'Eine eigene Instanz betreiben', path: 'run/' },
    ],
  },
  docs: {
    kicker: 'Dokumentation',
    title: 'Weiterlesen',
    items: [
      { title: 'Benutzen', who: 'Für alle, die veyq benutzen, und für Bereichs-Eigentümer. Auf Deutsch und Englisch.', path: 'start/' },
      { title: 'Betreiben', who: 'Für alle, die einen Server betreiben: installieren, einrichten, sichern, aktualisieren. Auf Englisch.', path: 'run/' },
      { title: 'Sicherheit', who: 'Für Forschende: Bedrohungsmodell, Kryptografie, bekannte Grenzen. Auf Englisch.', path: 'security/start-here/' },
    ],
  },
  footer: {
    license: 'veyq ist freie Software unter der GNU AGPL 3.0.',
    privacy: 'Diese Seite setzt keine Cookies, verfolgt niemanden und lädt nichts von Dritten.',
    source: 'Quellcode',
    report: 'Eine Schwachstelle melden',
  },
  placeholder: 'Screenshot folgt',
};

export const strings: Record<Lang, Strings> = { en, de };
