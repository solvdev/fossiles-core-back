# Instrucciones del proyecto (back)

## Flujo de ramas (desde 2026-11-17)

Todos los cambios entran primero a `release`, no a `main`: los PR se abren contra `release`, y cada merge ahí genera su pre-release `vX.Y.Z-rc.N` con la versión que corresponde al cambio:
- Módulo o funcionalidad nueva (`feat:` / label `minor`) → sube MENOR.
- Fix o ajuste → sube PARCHE.
- Rompe API o datos (label `major`) → sube MAYOR.
Asegura que el PR lleve el título o label correcto, porque de eso sale la versión. `main` solo recibe lo ya probado en `release` (promoción), y ahí se crea el tag final y el GitHub Release. El merge de `release` a `main` lo hace siempre el dueño del repo: Claude abre los PR contra `release` y nunca promueve a `main`.

## Versioning y releases (obligatorio)

Este repo usa versionado semántico con tags `vMAYOR.MENOR.PARCHE`.
Versión base (solo si aún no existe ningún tag final `v*`): `v1.<N>.0`, donde N = `git rev-list --merges --count origin/main` en ese momento. No la asumas ni la copies de aquí: calcúlala siempre (al 2026-11-17 daba back v1.269.0; sube con cada merge).

Cada vez que se haga merge a `release` o a `main`:
1. Antes de calcular nada, revisa qué existe de verdad: `git fetch --tags --force` y `git describe --tags --abbrev=0` (los repos están en carpetas con ramas de trabajo; usa `origin/main`, no la rama actual). Si no hay tag final, usa la versión base calculada arriba.
2. Calcula la siguiente versión:
   - PARCHE por defecto (fixes, ajustes).
   - MENOR si el PR agrega un módulo o funcionalidad nueva (label `minor` o título `feat:`).
   - MAYOR solo si rompe compatibilidad de API o datos (label `major`).
3. En `release`: crea un pre-release `vX.Y.Z-rc.N` (N sube con cada merge a release de esa versión).
   En `main`: crea el tag final `vX.Y.Z` y un GitHub Release.
4. Las notas del release listan los PR incluidos (número, título, autor), agrupados en Nuevo / Correcciones / Otros.
5. La versión debe quedar inyectada en el build (back: `GET /api/version` con `{version, commit, buildTime}`), nunca escrita a mano.
6. Si existe el GitHub Action de releases, no lo dupliques: verifica que corrió y que el tag y el release quedaron bien. Si falló, avísame antes de crearlo manualmente.
7. Nunca muevas ni borres un tag ya publicado. Si hubo un error, saca una versión nueva.
