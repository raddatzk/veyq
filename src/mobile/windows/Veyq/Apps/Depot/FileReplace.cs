// A file's Replace (ADR 0154 decision 3, inbox #1066; Linux's apps/depot/replace.rs): a member's, from its page's header,
// into the tenant's queue (Upload.Replace), and a READ_WRITE guest's, from the link's bar, into the link's own
// (Received.Replace, #1035) — each chosen on its page in the core first (File.ChooseReplace: the replace expects the
// content shown now), then the open picker picks the one file whose path goes to the core.

namespace Veyq.Apps.Depot;

using System;
using Microsoft.UI.Xaml;
using Veyq.Bridge;
using Veyq.Bridge.Wire;
using Veyq.Core;
using Windows.Storage.Pickers;

internal static class FileReplace
{
    /// <summary>A member's Replace of file <paramref name="file"/>, queued in the tenant's queue.</summary>
    public static async void Member(Window window, long file, Action<Spoken> say)
    {
        try
        {
            if (await Picked(window, file) is not { } path) return;
            var (answer, _) = await Core.CallAsync(new Call.UploadReplace { file = file, path = path });
            Uploads.Queued(answer, "Upload.Replace", say);
        }
        catch (Exception e)
        {
            Log.Write($"replace failed: {e.GetType().Name}: {e.Message}");
        }
    }

    /// <summary>A READ_WRITE guest's Replace of file <paramref name="file"/> on link <paramref name="received"/>, queued on
    /// the link's own queue — never a tenant's Upload.Replace; an unnamed guest is asked its name by the core at the write.</summary>
    public static async void Guest(Window window, long received, long file, Action<Spoken> say)
    {
        try
        {
            if (await Picked(window, file) is not { } path) return;
            var (answer, _) = await Core.CallAsync(new Call.ReceivedReplace { received = received, path = path });
            Log.Write($"a guest's replace: {answer.GetType().Name}");
            Uploads.Queued(answer, "Received.Replace", say);
        }
        catch (Exception e)
        {
            Log.Write($"a guest's replace failed ({e.GetType().Name})");
        }
    }

    /// <summary>Replace chosen on file <paramref name="file"/>'s page in the core, then the one file picked; its path, or
    /// null where the core refused or nothing was picked.</summary>
    private static async System.Threading.Tasks.Task<string?> Picked(Window window, long file)
    {
        var (chosen, _) = await Core.CallAsync(new Call.FileChooseReplace { file = file });
        if (chosen is not Answer.Done)
        {
            Log.Write($"File.ChooseReplace answered {chosen.GetType().Name}");
            return null;
        }
        var picker = new FileOpenPicker { SuggestedStartLocation = PickerLocationId.DocumentsLibrary };
        picker.FileTypeFilter.Add("*");
        WinRT.Interop.InitializeWithWindow.Initialize(picker, WinRT.Interop.WindowNative.GetWindowHandle(window));
        var picked = await picker.PickSingleFileAsync();
        return picked is null || string.IsNullOrEmpty(picked.Path) ? null : picked.Path;
    }
}
