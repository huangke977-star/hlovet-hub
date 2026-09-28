export const dynamic = "force-dynamic";

export async function GET() {
  const packageName = process.env.ANDROID_APP_PACKAGE_NAME?.trim() || "xyz.hlovet.portal.prototype";
  const fingerprints = (process.env.ANDROID_APP_SHA256_CERT_FINGERPRINT ?? "")
    .split(",")
    .map((value) => value.trim().toUpperCase())
    .filter(Boolean);

  return Response.json(
    fingerprints.map((fingerprint) => ({
      relation: [
        "delegate_permission/common.get_login_creds",
        "delegate_permission/common.handle_all_urls",
      ],
      target: {
        namespace: "android_app",
        package_name: packageName,
        sha256_cert_fingerprints: [fingerprint],
      },
    })),
    {
      headers: {
        "Cache-Control": "public, max-age=300, must-revalidate",
      },
    },
  );
}
