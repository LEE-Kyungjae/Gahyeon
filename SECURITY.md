# Security Policy

## Supported versions

Security fixes are applied to the latest commit on `main`. Development branches and
older images are not supported releases.

## Reporting a vulnerability

Do not open a public issue for a suspected vulnerability. Use GitHub's
[private vulnerability reporting](https://github.com/LEE-Kyungjae/Gahyeon/security/advisories/new)
to provide:

- the affected commit or released image;
- the component and configuration involved;
- reproduction steps or a minimal proof of concept;
- the expected and observed security impact; and
- any known workaround.

Please do not access other users' data, disrupt a running service, or publish the
finding before a fix and coordinated disclosure are ready.

## Security boundaries

- Client APIs accept loopback traffic only unless `GAHYEON_CLIENT_TOKEN` is set.
- Discord, Desktop, Unreal, STT, TTS, and model providers are adapters outside the
  platform-neutral Core trust boundary.
- Tool execution, identity linking, durable event acknowledgements, and renderer
  connections must fail closed when authentication or validation is uncertain.
- Secrets, source voice recordings, training checkpoints, private identity material,
  and licensed character assets must not be committed to Git or baked into images.
- Generated character artifacts are not trusted runtime assets until their manifests,
  checksums, provenance, and quality approvals have passed.

See [the architecture documentation](docs/ARCHITECTURE.md) and
[deployment guide](docs/DEPLOYMENT.md) for the current runtime and operational boundaries.
