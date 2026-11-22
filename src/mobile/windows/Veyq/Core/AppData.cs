// The app's data folder on this device (Veyq.Bridge's DataFolder holds the rule): the package's own local folder when
// this process has package identity (the MSIX), %LOCALAPPDATA%\veyq when it has none (the development loop's unpackaged
// build) — or, in a debug build, VEYQ_DATA_DIR where a pass's run names one (windows/run.py: <VEYQ_WINDOWS_DIR>\data,
// inbox #1052). Resolved once, before the log or the device store opens.

namespace Veyq.Core;

using System;
using System.Runtime.InteropServices;
using Veyq.Bridge;

internal static partial class AppData
{
    private const int AppModelErrorNoPackage = 15700;

    public static string Folder { get; } =
        DataFolder.Resolve(PackageLocalFolder(), Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), PassFolder());

    /// <summary>A debug build's VEYQ_DATA_DIR (a pass's own folder); a release build reads none.</summary>
    private static string? PassFolder()
    {
#if DEBUG
        return Environment.GetEnvironmentVariable("VEYQ_DATA_DIR") is { Length: > 0 } d ? d : null;
#else
        return null;
#endif
    }

    /// <summary>Whether this process runs with package identity (an installed MSIX).</summary>
    public static bool Packaged
    {
        get
        {
            uint length = 0;
            return GetCurrentPackageFullName(ref length, IntPtr.Zero) != AppModelErrorNoPackage;
        }
    }

    // ApplicationData.Current throws without package identity, so it is asked only with one.
    private static string? PackageLocalFolder() => Packaged ? Windows.Storage.ApplicationData.Current.LocalFolder.Path : null;

    [LibraryImport("kernel32.dll")]
    private static partial int GetCurrentPackageFullName(ref uint packageFullNameLength, IntPtr packageFullName);
}
