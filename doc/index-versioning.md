# FileBasedIndex versioning and externalizer compatibility

Every `FileBasedIndex` of this plugin is covered by a contract test
(`de.shyim.shopware6.test.contract.IndexContractTest`) that

- collects at least one fixture per index from `src/test/testData/contract/<IndexClass>/`,
- round-trips every indexed value through the index value externalizer
  (serialize → deserialize → serialize must be byte-stable), and
- compares the index version and a SHA-256 over the serialized keys and values with
  `src/test/resources/index-contract/snapshots.properties`.

The manifest of covered indexes lives in `src/test/resources/index-contract/manifest.txt` and is
kept in sync with `plugin.xml` and the index sources by the `verifyIndexContractManifest` task
(part of `./gradlew verifyPluginContract`). A new, renamed or removed index fails the build until
the manifest, the fixtures and the snapshots are updated.

## When the snapshot check fails

A snapshot mismatch means the persistent form of an index changed. Decide deliberately:

1. **Index semantics changed** (new fields, different keys, changed indexer):
   bump `getVersion()` of the index so the IDE drops stale index data on upgrade,
   then regenerate the snapshots:

   ```bash
   ./gradlew test -Pindex.contract.updateSnapshots=true
   ```

2. **Only the externalizer bytes changed** (e.g. different serialization of the same data):
   the on-disk index data of existing users becomes unreadable or changes meaning.
   Bump `getVersion()` as well — there is no migration path for `FileBasedIndex` values —
   and regenerate the snapshots.

3. **The change is a false positive** (fixture files were edited intentionally):
   review the fixture diff, then regenerate the snapshots.

Record every intentional change in the compatibility log below before regenerating.

## Compatibility log

| Index version change | Reason | Release |
| --- | --- | --- |
| Baseline snapshots recorded for all 18 indexes | Contract test introduction | Unreleased |

## Notes

- `ObjectStreamDataExternalizer` uses Java serialization. Field changes of the `dict` classes are
  not compatible with previously serialized data; always bump the owning index version.
- Snapshot hashes are computed from fixtures copied into the light test fixture (`/src/...` paths),
  so they are stable across machines as long as fixtures and index logic stay unchanged.
