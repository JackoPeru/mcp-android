# Limiti condivisi bridge/app (v0.8.19)

Tabella unica dei limiti validati da entrambi i lati. Il bridge rifiuta prima dell'invio; l'app rivalida prima dell'esecuzione.

| Area | Limite |
|---|---|
| `rootId` + `path` SAF | `rootId` da `android_file_roots`, `path` relativo senza `..`, senza assoluti/URI/doppi slash/backslash/NUL |
| `shellWorkdir` | max 1024 char, senza NUL/controlli né componenti `..`; default `''` |
| `package` / `workdir` | nomi package Android validi; workdir esistente rivalidata sul telefono |
| Coordinate assolute | interi `0..16384` |
| Coordinate normalizzate | interi `0..1000`, un solo sistema completo per punto (assolute o normalizzate, mai miste/parziali) |
| `android_flow` | `1..40` step, `timeoutMs 100..20000` (default 18000), no shell/file-mutation/force-stop dentro il flow |
| `android_batch` | `1..20` step UI/system validati, no screenshot/shell/file-mutations; stop a primo `outcome_unknown` |
| JSON RPC | envelope max 64 KiB, profondità max 16; testi/notifiche/file/shell trattati come dati non attendibili |
| File read | `length 1..262144` (default 65536); oltre primo blocco su stream non-seekable `SEEK_UNSUPPORTED` |
| File write | blocchi base64 max 32 KiB, `truncate: true` solo sul primo blocco di sostituzione |
| File search | `query` max 255 char, `maxDepth 0..16` (default 8), `limit 1..500` (default 100) |
| Snapshot/diff | max 8 snapshot in memoria; trace journal max 128 entry metadata-only |
| Discovery LAN | UDP 8766, max 512 byte, rate 1 risposta/s per IP, max 64 sorgenti in RAM |
| Shell | `script 1..32768` char, `stdin` max 32768 char, `timeoutMs 250..12000` (default 8000) |
| Socket/retry | I/O socket 8 s, deadline telefono 25 s, bridge 30 s; mai replay automatico di mutazioni con esito incerto (`outcome_unknown`) |
