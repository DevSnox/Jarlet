# Jarlet Versioning

Jarlet versions follow semantic versioning.
[Semantic Versioning](https://semver.org/).

## Release Cycle
```text
alpha → beta → rc → stable
```

* `alpha`: Jarlet version is still being built, but can be experimentally tested.
* `beta`: All main pieces are there, but bugs remain.
* `rc`: Jarlet version looks finished and needs final testing.
* `stable`: Jarlet version is ready for normal use.

Every testable release is numbered:

```text
0.1.0-alpha.1
0.1.0-alpha.2
0.1.0-beta.1
0.1.0-rc.1
0.1.0
```

When moving to the next stage, the number starts at `1`.

Store the current version in `VERSION`:

```text
0.1.0-alpha.1
```

Use the same version for the Git tag, with `v` added:

```text
v0.1.0-alpha.1
```

Do not change a version after publishing it. Create the next numbered version instead.
