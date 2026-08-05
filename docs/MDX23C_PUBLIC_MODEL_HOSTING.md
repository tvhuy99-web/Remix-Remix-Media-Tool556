# MDX23C public model hosting

The application repository is public, so Android can download the pinned MDX23C release asset
without a GitHub token. The application keeps the APK small and uses `ModelDownloader` rather than
embedding the ONNX file in Android assets.

## Active public artifact

- Release tag: `mdx23c-vocal-personal-v1`
- Asset: `mdx23c-vocals-core.onnx`
- Download URL:
  `https://github.com/tvhuy99-web/Remix-Remix-Media-Tool556/releases/download/mdx23c-vocal-personal-v1/mdx23c-vocals-core.onnx`
- Expected bytes: `448152790`
- SHA-256: `8925ece1f0da006d342856f93e75ba2dea9058d44c286c4cd6a98a41c67367bb`

The `verify-mdx23c-public-model.yml` workflow calls the release API and direct asset URL without
`GH_TOKEN`. This catches visibility regressions or accidental asset deletion before an APK is shared.

## Android behavior

1. `StemViewModel` uses the shared resumable `ModelDownloader` for MDX23C.
2. The downloader follows GitHub redirects, requests identity encoding and resumes with HTTP Range.
3. The completed file is accepted only after exact byte-size and SHA-256 validation.
4. Partial downloads remain in `filesDir/models` so the user can pause and continue later.
5. The normal APK does not contain the 448 MB ONNX asset.

## Removed bundled path

- `BundledModelInstaller`
- bundled-model Android asset
- bundled APK workflow and split artifacts
- ONNX `noCompress` packaging rule

Keep the release tag and asset name stable. Publishing a replacement model requires a new filename,
new expected byte count and new SHA-256 in `StemModelRegistry`; never replace the pinned asset
silently under the same metadata.
