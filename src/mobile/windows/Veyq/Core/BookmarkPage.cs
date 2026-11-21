// A kept link's page (ADR 0150 decision 6; the web's BookmarkDetail.vue and BookmarkEmbed.vue; cr-app-received-links
// phase 10), Fluent's way — Linux's bookmark_page.rs, in WinUI: core's BookmarkPage (Bookmark.Open, Watched.Bookmark),
// shown in the shell's content under the bookmark's name (a kept link is no application's object, so the shell opens
// none: its row opens this page, ShellView holds it until Back). While the tenant has core.share-embedding on, the share
// shows in place — a ReceivedView in place, a region named "Shared content from …" —; otherwise the link's host on its
// own. Either way its header's plan (the shell's CommandBar) holds Open on its own page (the link's screen above
// everything, its password handed in memory by the core) and Report shared content (that screen with its report asked, to
// the share's own operator) once its meta reads — this page tells the header its id (Opened) and does both (OwnPage) —,
// and the core's Report, a name's report for a bookmark. A meta that does not read says so.
//
// The page never holds the link, its password or its key: the core opens the meta and hands this side ids alone.

namespace Veyq.Core;

using System;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Veyq.Bridge;
using Veyq.Bridge.Wire;

internal sealed class BookmarkPage : IDisposable
{
    /// <summary>The kind of a kept link (core's AppKinds.BOOKMARK_KIND).</summary>
    public const string Kind = "SHARE_BOOKMARK";

    private readonly Window _window;
    private readonly ContentControl _root = new() { HorizontalContentAlignment = HorizontalAlignment.Stretch, VerticalContentAlignment = VerticalAlignment.Stretch };
    private long? _id;
    private Watch? _watch;
    private BookmarkShown? _shown;
    private ReceivedView? _inPlace;
    private bool _closed;

    public NodeAt Node { get; }

    public UIElement Page => _root;

    /// <summary>Told when what the CommandBar offers changed (the meta read).</summary>
    public Action? Changed { get; set; }

    /// <summary>Told the page's id once the core opened it (Header.Facts' bookmark: its own entries join the plan).</summary>
    public Action<long>? Opened { get; set; }

    public BookmarkPage(Window window, NodeAt node)
    {
        _window = window;
        Node = node;
        AutomationProperties.SetAutomationId(_root, "bookmark-page");
        Draw();
        Open();
    }

    private async void Open()
    {
        try
        {
            var (answer, _) = await Core.CallAsync(new Call.BookmarkOpen { node = Node });
            if (answer is not Answer.BookmarkOpened { bookmark: var bookmark })
            {
                Log.Write($"the bookmark did not open in the core ({answer.GetType().Name})");
                return;
            }
            if (_closed)
            {
                Act.Send(new Call.BookmarkClose { bookmark = bookmark });
                return;
            }
            _id = bookmark;
            Opened?.Invoke(bookmark);
            _watch = Core.Watch(new Watched.Bookmark { bookmark = bookmark }, update =>
            {
                if (_closed || update is not Update.Bookmark b) return;
                Log.Write($"bookmark: {b.shown?.state.ToString() ?? "closed"}");
                _shown = b.shown;
                Draw();
                Changed?.Invoke();
            }, e => Log.Write($"a bookmark update: {e.Message}"));
        }
        catch (Exception e)
        {
            // an exception out of an async void would end the app in combase, with nothing in the log
            Log.Write($"the bookmark did not open ({e.GetType().Name})");
        }
    }

    public void Dispose()
    {
        _closed = true;
        _watch?.Dispose();
        _watch = null;
        _inPlace?.Dispose();
        _inPlace = null;
        if (_id is { } bookmark) Act.Send(new Call.BookmarkClose { bookmark = bookmark });
        _id = null;
    }

    private void Draw()
    {
        // the share in place stays while the same link shows; anything else is drawn anew
        if (_shown is { received: { } same } && _inPlace?.Received == same) return;
        _inPlace?.Dispose();
        _inPlace = null;
        switch (_shown)
        {
            case null or { state: BookmarkState.Loading }:
                _root.Content = new ProgressRing { IsActive = true, Width = 32, Height = 32, HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center };
                break;
            case { state: BookmarkState.Unreadable }:
                _root.Content = Said(Words.BookmarkUnreadable(), "bookmark-unreadable");
                break;
            case { state: BookmarkState.OnItsOwn } s:
                _root.Content = Said(s.host, "bookmark-host");
                break;
            case { state: BookmarkState.InPlace, received: { } received } s:
            {
                _inPlace = new ReceivedView(_window, received, ReceivedMode.InPlace, null, null);
                // the region named for assistive technology: whose content this is
                var region = new Grid();
                region.Children.Add(_inPlace.Page);
                AutomationProperties.SetLandmarkType(region, Microsoft.UI.Xaml.Automation.Peers.AutomationLandmarkType.Custom);
                AutomationProperties.SetLocalizedLandmarkType(region, Words.BookmarkEmbedRegion(s.host));
                AutomationProperties.SetName(region, Words.BookmarkEmbedRegion(s.host));
                _root.Content = region;
                break;
            }
        }
    }

    private static UIElement Said(string text, string automationId)
    {
        var said = new TextBlock { Text = text, TextWrapping = TextWrapping.Wrap, Style = (Style)Application.Current.Resources["BodyStrongTextBlockStyle"], Margin = new Thickness(24), HorizontalAlignment = HorizontalAlignment.Center };
        AutomationProperties.SetAutomationId(said, automationId);
        return said;
    }

    /// <summary>Open on its own page, or (<paramref name="report"/>) Report shared content: the link's screen above
    /// everything, its report asked for the latter.</summary>
    public async void OwnPage(bool report)
    {
        try
        {
            if (_id is not { } bookmark) return;
            Call call = report ? new Call.BookmarkReportShared { bookmark = bookmark } : new Call.BookmarkOwnPage { bookmark = bookmark };
            var (answer, _) = await Core.CallAsync(call);
            if (answer is Answer.ReceivedOpened { received: var received }) ReceivedView.Present(received, null);
            else Log.Write($"the bookmark's link did not open ({answer.GetType().Name})");
        }
        catch (Exception e)
        {
            Log.Write($"the bookmark's link did not open ({e.GetType().Name})");
        }
    }
}
