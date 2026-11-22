using System.IO;

namespace Veyq.Bridge;

/// <summary>
/// Where the app keeps its data on this device (the device store, the log, the offline stores). The packaged app (the MSIX) keeps it in its
/// package's own folder (<c>ApplicationData.Current.LocalFolder</c>: <c>%LOCALAPPDATA%\Packages\&lt;family&gt;\LocalState</c>),
/// so it starts as a clean install does and its uninstall takes it along; an unpackaged build (the development loop's)
/// keeps it in <c>%LOCALAPPDATA%\veyq\</c>. The two never share it: a packaged app left to plain <c>%LOCALAPPDATA%</c>
/// wrote <c>%LOCALAPPDATA%\veyq</c> in place wherever an unpackaged build had made it, so every development pass signed
/// in, out and untrusted the device state of the installed app (2026-11-14).
/// </summary>
public static class DataFolder
{
    /// <summary>The unpackaged build's folder under <paramref name="localAppData"/>.</summary>
    public static string Unpackaged(string localAppData) => Path.Combine(localAppData, "veyq");

    /// <summary>
    /// The data folder: <paramref name="packageLocalFolder"/> (the package's own local folder, given only when the process
    /// has package identity) whenever there is one, whatever is under <paramref name="localAppData"/> or
    /// <paramref name="passFolder"/> says; else <paramref name="passFolder"/> where one is named; else <see cref="Unpackaged"/>.
    /// </summary>
    /// <remarks>
    /// <paramref name="passFolder"/> is a debug build's <c>VEYQ_DATA_DIR</c>, which <c>windows/run.py run</c> sets to
    /// <c>&lt;VEYQ_WINDOWS_DIR&gt;\data</c>: each pass's device then lives beside its own app and goes with its directory.
    /// Every pass shared <c>%LOCALAPPDATA%eyq</c> before, whatever directory it named, so one pass's start met the
    /// password trust another pass's sign-out had kept, of a throwaway erased since (inbox #1052: ten such records on the
    /// test VM, 2026-11-22). The packaged app never takes it.
    /// </remarks>
    public static string Resolve(string? packageLocalFolder, string localAppData, string? passFolder = null) =>
        !string.IsNullOrEmpty(packageLocalFolder) ? packageLocalFolder
        : !string.IsNullOrEmpty(passFolder) ? passFolder
        : Unpackaged(localAppData);

    /// <summary>
    /// The accounts' offline stores (ADR 0140 decision 3) in <paramref name="dataFolder"/> (<see cref="Resolve"/>'s):
    /// <c>%LOCALAPPDATA%\veyq\offline</c> unpackaged, the package's own <c>LocalState\offline</c> under the MSIX — removed
    /// with the package, never roamed. The core makes it and fills it; the data folder is made here first.
    /// </summary>
    public static string Offline(string dataFolder)
    {
        Directory.CreateDirectory(dataFolder);
        return Path.Combine(dataFolder, "offline");
    }
}
