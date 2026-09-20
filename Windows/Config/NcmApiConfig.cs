namespace ZMusic.Config;

/// <summary>
/// NeteaseCloudMusicApi base URL.
/// Override via env <c>ZMUSIC_NCM_API_BASE_URL</c> or runtime; no public IP is baked into the repo.
/// </summary>
public static class NcmApiConfig
{
    private static string? _runtimeBaseUrl;

    public static string BaseUrl
    {
        get
        {
            if (!string.IsNullOrWhiteSpace(_runtimeBaseUrl))
            {
                return _runtimeBaseUrl.TrimEnd('/');
            }

            var fromEnv = Environment.GetEnvironmentVariable("ZMUSIC_NCM_API_BASE_URL");
            if (!string.IsNullOrWhiteSpace(fromEnv))
            {
                return fromEnv.Trim().TrimEnd('/');
            }

            return string.Empty;
        }
    }

    public static void SetRuntimeBaseUrl(string? url)
    {
        _runtimeBaseUrl = string.IsNullOrWhiteSpace(url)
            ? null
            : url.Trim().TrimEnd('/');
    }
}
