# MDX23C public model hosting

The application repository is private, so unauthenticated Android downloads of its release assets
return HTTP 404. The final slim APK should download the pinned ONNX file from a separate public
repository owned by the same personal GitHub account.

## Recommended layout

1. Create a public repository such as `tvhuy99-web/MediaTool-Personal-Models`.
2. Create release tag `mdx23c-vocal-personal-v1`.
3. Upload `mdx23c-vocals-core.onnx` as a release asset, not as a Git history blob.
4. Verify the asset is exactly 448,152,790 bytes and SHA-256 is
   `8925ece1f0da006d342856f93e75ba2dea9058d44c286c4cd6a98a41c67367bb`.
5. Open the download URL in a private browser window and confirm it returns the file without login.
6. Update `StemModelRegistry.mdx23cVocalPersonal.modelSpec.url` to the public release URL.
7. Remove `BundledModelInstaller`, the bundled APK workflow and ONNX asset packaging.

Keep the private application repository private. Only the model-host repository needs to be public.
