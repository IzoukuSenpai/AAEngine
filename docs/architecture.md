# Architecture

Recommended core flow:

```text
Bootstrap -> Config -> Version Bridge -> Content Snapshot -> Mechanics -> Pack Compiler -> Runtime
```

The central rule is immutable compiled snapshots and atomic replacement on reload.
