// A Memory's Contributions view (ADR 0149 decision 6; the web's MemoryBuckets.vue; Apple's ContributionsView), the fourth
// item of the page's SelectorBar where GalleryShown.contributions holds (a member's): the uploader picker — a ComboBox of
// every contributor's primary bucket with the sub-buckets curated below it, each with its count — narrowing to the chosen
// bucket's media, drawn as square tiles at the core's frames (View.Tiles), a tile opening the lightbox; on the caller's own
// bucket a More menu with New sub-bucket, and a sub-bucket's Rename and Delete bucket (its name typed to confirm, as the
// web's); a medium of one's own moved into another of one's buckets from its tile's right-click menu. Writes are online
// only (the core refuses them offline); what it refused is said once, then told seen (Buckets.Seen).
//
// Every state is the core's (Buckets.Open, Watched.Buckets: BucketsShown), every action one call (Buckets.*). Nothing is
// decided here; nothing decrypted is written to show it (ADR 0047).
using System;
using System.Collections.Generic;
using System.Linq;
using System.Threading.Tasks;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Veyq.Bridge;
using Veyq.Bridge.Wire;

namespace Veyq;

internal sealed class ContributionsView : IDisposable
{
    /// <summary>The longest side a tile's thumbnail is decoded at.</summary>
    private const int TileEdge = 400;

    private readonly Window _window;
    private readonly long _gallery;
    private readonly Action<bool, string> _say;
    /// <summary>A medium's row on the page (its name and tile) and its place in the page's rows; null where the page does
    /// not show it now.</summary>
    private readonly Func<string, (GalleryRow Row, int Index)?> _row;
    private readonly Action<int> _open;
    /// <summary>Whether the page reads from the offline store: nothing is curated then.</summary>
    private readonly Func<bool> _offline;
    private readonly StackPanel _view = new() { Spacing = 8 };
    private readonly ComboBox _picker = new() { MinWidth = 240 };
    private readonly DropDownButton _more = new() { Content = new FontIcon { Glyph = "\uE712", FontSize = 14 } };
    private readonly ContentControl _status = new() { HorizontalContentAlignment = HorizontalAlignment.Stretch };
    private readonly Canvas _canvas = new();
    private readonly Grid _grid = new() { HorizontalAlignment = HorizontalAlignment.Stretch };
    private readonly Recent<ImageSource> _thumbs = new(200);
    private long? _id;
    private Watch? _watch;
    private BucketsShown? _shown;
    private string _pickerDrawn = "";
    private string _gridDrawn = "";
    private double _laid = -1;
    private int _asked;
    private bool _drawing;
    private bool _refusedSaid;
    /// <summary>What the last write was, for the words of its refusal.</summary>
    private bool _moving;
    private bool _closed;

    public UIElement View => _view;

    public ContributionsView(Window window, long gallery, Action<bool, string> say, Func<string, (GalleryRow Row, int Index)?> row, Action<int> open, Func<bool> offline)
    {
        _window = window;
        _gallery = gallery;
        _say = say;
        _row = row;
        _open = open;
        _offline = offline;
        AutomationProperties.SetAutomationId(_view, "memory-buckets");
        AutomationProperties.SetAutomationId(_picker, "memory-buckets-picker");
        AutomationProperties.SetName(_picker, Words.MemoryBucketsTitle());
        _picker.SelectionChanged += (_, _) =>
        {
            if (_drawing || _id is not { } buckets || _picker.SelectedItem is not ComboBoxItem { Tag: string bucket } || bucket == _shown?.selected) return;
            Log.Write("contributions: a bucket picked");
            Act.Send(new Call.BucketsPick { buckets = buckets, bucketId = bucket });
        };
        AutomationProperties.SetAutomationId(_more, "memory-buckets-more");
        AutomationProperties.SetName(_more, Words.MemoryBucketsOptions());
        ToolTipService.SetToolTip(_more, Words.MemoryBucketsOptions());
        var head = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8 };
        head.Children.Add(_picker);
        head.Children.Add(_more);
        _view.Children.Add(head);
        _view.Children.Add(_status);
        AutomationProperties.SetAutomationId(_grid, "memory-buckets-grid");
        _grid.Children.Add(_canvas);
        _grid.SizeChanged += (_, e) => Layout(e.NewSize.Width);
        _view.Children.Add(_grid);
        Open();
    }

    private async void Open()
    {
        try
        {
            var (answer, _) = await Core.CallAsync(new Call.BucketsOpen { gallery = _gallery });
            if (answer is not Answer.BucketsOpened { buckets: var buckets })
            {
                Log.Write($"Buckets.Open answered {(answer is Answer.Refused r ? $"Refused {r.why}" : answer.GetType().Name)}");
                return;
            }
            if (_closed)
            {
                Act.Send(new Call.BucketsClose { buckets = buckets });
                return;
            }
            _id = buckets;
            _watch = Core.Watch(new Watched.Buckets { buckets = buckets }, Updated, e => Log.Write($"a contributions update: {e.Message}"));
        }
        catch (Exception e)
        {
            // an exception out of an async void would end the app in combase, with nothing in the log
            Log.Write($"the contributions did not open ({e.GetType().Name})");
        }
    }

    private void Updated(Update update)
    {
        if (_closed || update is not Update.Buckets { shown: var s }) return;
        _shown = s;
        if (s is null) return;
        Log.Write($"contributions shown: {s.rows.Count} buckets, selected {s.selected is not null}, {s.media.Count} media, curates {s.curates}, loading {s.loading}, failed {s.failed}, refused {s.refused?.ToString() ?? "-"}");
        Draw(s);
    }

    /// <summary>The page's rows changed: the chosen bucket's media are derived anew from them (Buckets.Pick of the same
    /// bucket — a curation's own re-read runs before the gallery's reload lands, inbox #990, so a moved medium shows gone
    /// only once the rows did; Android re-reads on the rows too), and the tiles drawn anew.</summary>
    public void RowsChanged()
    {
        if (_id is { } buckets && _shown?.selected is { } selected) Act.Send(new Call.BucketsPick { buckets = buckets, bucketId = selected });
        if (_shown is { } s) Draw(s);
    }

    private void Draw(BucketsShown s)
    {
        _drawing = true;
        try
        {
            var picker = string.Join("|", s.rows.Select(r => $"{r.bucketId}:{Said.Piece(r.label)}:{r.depth}:{r.mediaCount}")) + "#" + s.selected;
            if (picker != _pickerDrawn)
            {
                _pickerDrawn = picker;
                _picker.Items.Clear();
                foreach (var r in s.rows)
                {
                    var item = new ComboBoxItem { Content = RowWords(r), Tag = r.bucketId };
                    AutomationProperties.SetAutomationId(item, $"memory-bucket:{Said.Piece(r.label)}");
                    _picker.Items.Add(item);
                    if (r.bucketId == s.selected) _picker.SelectedItem = item;
                }
            }
            _picker.Visibility = s.rows.Count > 0 ? Visibility.Visible : Visibility.Collapsed;
        }
        finally
        {
            _drawing = false;
        }
        var row = s.rows.FirstOrDefault(r => r.bucketId == s.selected);
        var curates = s.curates && !_offline();
        _more.Visibility = curates ? Visibility.Visible : Visibility.Collapsed;
        if (curates) _more.Flyout = More(row);
        _status.Content = s switch
        {
            { loading: true, rows.Count: 0 } => new ProgressRing { IsActive = true, Margin = new Thickness(24), HorizontalAlignment = HorizontalAlignment.Center },
            { failed: true, rows.Count: 0 } => Line(Words.MemoryGalleryFailed(), "memory-buckets-failed"),
            { media.Count: 0, rows.Count: > 0 } => Line(Words.MemoryBucketsEmptyBucket(), "memory-buckets-empty"),
            _ => null,
        };
        var grid = string.Join(",", s.media.Select(m => $"{m}:{_row(m)?.Index}")) + $"#{curates}#{string.Join(",", s.rows.Where(r => r.canManage).Select(r => r.bucketId))}#{s.selected}";
        if (grid != _gridDrawn)
        {
            _gridDrawn = grid;
            _laid = -1;
            Layout(_grid.ActualWidth);
        }
        // what the core refused, said once, then told seen
        if (s.refused is null) _refusedSaid = false;
        else if (!_refusedSaid && _id is { } buckets)
        {
            _refusedSaid = true;
            _say(false, _moving ? Words.MemoryBucketsMoveFailed() : Words.MemoryBucketsSaveFailed());
            Act.Send(new Call.BucketsSeen { buckets = buckets });
        }
    }

    /// <summary>A bucket as the picker lists it: indented by its depth, its count after its name (Apple's rowWords).</summary>
    private static string RowWords(BucketRowShown r) => new string(' ', 4 * r.depth) + Said.Piece(r.label) + " · " + r.mediaCount;

    private static TextBlock Line(string text, string id)
    {
        var line = new TextBlock { Text = text, TextWrapping = TextWrapping.Wrap, Margin = new Thickness(0, 12, 0, 12) };
        line.Foreground = (Brush)Application.Current.Resources["TextFillColorSecondaryBrush"];
        AutomationProperties.SetAutomationId(line, id);
        return line;
    }

    /// <summary>The More menu on the caller's own bucket: New sub-bucket; a sub-bucket's Rename and Delete bucket.</summary>
    private MenuFlyout More(BucketRowShown? row)
    {
        var menu = new MenuFlyout { Placement = Microsoft.UI.Xaml.Controls.Primitives.FlyoutPlacementMode.BottomEdgeAlignedLeft };
        MenuFlyoutItem Item(string text, string glyph, string id, Func<Task> act)
        {
            var item = new MenuFlyoutItem { Text = text, Icon = new FontIcon { Glyph = glyph } };
            AutomationProperties.SetAutomationId(item, id);
            item.Click += async (_, _) =>
            {
                try
                {
                    await act();
                }
                catch (Exception e)
                {
                    Log.Write($"a contributions write failed ({e.GetType().Name})");
                }
            };
            return item;
        }
        menu.Items.Add(Item(Words.MemoryBucketsCreateBucket(), "\uE8F4", "memory-buckets-create", Create));
        if (row is { isPrimary: false })
        {
            var name = Said.Piece(row.label);
            menu.Items.Add(Item(Words.MemoryBucketsRename(), "\uE8AC", "memory-buckets-rename", () => Rename(name)));
            menu.Items.Add(Item(Words.MemoryBucketsDelete(), "\uE74D", "memory-buckets-delete", () => Delete(name)));
        }
        return menu;
    }

    private async Task Create()
    {
        if (_id is not { } buckets) return;
        var typed = await Dialogs.AskName(_window, "bucket-create", Words.MemoryBucketsCreateBucket(), Words.MemoryBucketsName(), "", null, Words.FolderCreate());
        if (typed is null) return;
        await Write(new Call.BucketsCreate { buckets = buckets, name = typed }, moving: false);
    }

    private async Task Rename(string name)
    {
        if (_id is not { } buckets) return;
        var typed = await Dialogs.AskName(_window, "bucket-rename", Words.MemoryBucketsBucketDialogRename(), Words.MemoryBucketsName(), name, null, Words.MemoryBucketsRename());
        if (typed is null) return;
        await Write(new Call.BucketsRename { buckets = buckets, name = typed }, moving: false);
    }

    /// <summary>Delete bucket: the web's question, its name typed to confirm; its media go back to the primary.</summary>
    private async Task Delete(string name)
    {
        if (_id is not { } buckets) return;
        var box = new TextBox { Header = Words.MemoryBucketsDeleteBucketNameLabel(), MinWidth = 320 };
        AutomationProperties.SetAutomationId(box, "dialog-name");
        var content = new StackPanel { Spacing = 12 };
        content.Children.Add(new TextBlock { Text = Words.MemoryBucketsDeleteBucketCopy(name), TextWrapping = TextWrapping.Wrap });
        content.Children.Add(box);
        var dialog = new ContentDialog
        {
            Title = Words.MemoryBucketsDeleteBucketTitle(),
            Content = content,
            PrimaryButtonText = Words.MemoryBucketsDeleteBucketConfirm(),
            CloseButtonText = Words.EntryCancel(),
            DefaultButton = ContentDialogButton.Close,
            IsPrimaryButtonEnabled = false,
        };
        AutomationProperties.SetAutomationId(dialog, "bucket-delete");
        box.TextChanged += (_, _) => dialog.IsPrimaryButtonEnabled = box.Text == name;
        box.Loaded += (_, _) => box.Focus(FocusState.Programmatic);
        if (await Dialogs.Show(_window, dialog) != ContentDialogResult.Primary) return;
        await Write(new Call.BucketsDelete { buckets = buckets }, moving: false);
    }

    private async Task Write(Call call, bool moving)
    {
        _moving = moving;
        var (answer, _) = await Core.CallAsync(call);
        Log.Write($"contributions {call.GetType().Name}: {(answer is Answer.BucketsDone d ? d.outcome.ToString() : answer.GetType().Name)}");
    }

    /// <summary>The chosen bucket's media as square tiles at the core's frames (View.Tiles), asked anew when the width changes.</summary>
    private async void Layout(double width)
    {
        if (_closed || _shown is not { } s || width <= 0 || Math.Abs(width - _laid) < 0.5) return;
        _laid = width;
        var shown = s.media.Select(m => (Id: m, At: _row(m))).Where(m => m.At is not null).Select(m => (m.Id, At: m.At!.Value)).ToList();
        var ask = ++_asked;
        try
        {
            var (answer, _) = await Core.CallAsync(new Call.ViewTiles { width = width, count = shown.Count, labelHeight = 0 });
            if (_closed || ask != _asked) return;
            if (answer is not Answer.Tiles t)
            {
                Log.Write($"View.Tiles answered {answer.GetType().Name}");
                return;
            }
            _canvas.Children.Clear();
            var bottom = 0.0;
            for (var i = 0; i < shown.Count && i < t.frames.Count; i++)
            {
                var f = t.frames[i];
                // the tile's frame at the core's place: a grid's cell in a Border, nothing laid over a view
                var cell = new Border { Width = f.size, Height = f.size, CornerRadius = new CornerRadius(4), Child = Tile(shown[i].At.Row, shown[i].At.Index, f.size, s) };
                Canvas.SetLeft(cell, f.x);
                Canvas.SetTop(cell, f.y);
                _canvas.Children.Add(cell);
                bottom = Math.Max(bottom, f.y + f.size);
            }
            _grid.Height = Math.Ceiling(bottom);
        }
        catch (Exception e)
        {
            Log.Write($"the contributions' tiles were not laid out ({e.GetType().Name})");
        }
    }

    private Button Tile(GalleryRow row, int index, double size, BucketsShown s)
    {
        var name = row.name ?? Words.MemoryUnnamed(index + 1);
        var face = new Grid();
        var glyph = HomeView.Glyph(row.blocked ? "\uE733" : row.video ? "\uE714" : "\uE91B");
        glyph.FontSize = 32;
        glyph.HorizontalAlignment = HorizontalAlignment.Center;
        glyph.VerticalAlignment = VerticalAlignment.Center;
        glyph.Foreground = (Brush)Application.Current.Resources["TextFillColorSecondaryBrush"];
        face.Children.Add(glyph);
        if (row.tile is { } blob)
        {
            var image = new Image { Stretch = Stretch.UniformToFill, Width = size, Height = size };
            face.Children.Add(image);
            Thumb(image, glyph, row, blob);
        }
        var tile = new Button
        {
            Content = face,
            Width = size,
            Height = size,
            Padding = new Thickness(0),
            BorderThickness = new Thickness(0),
            CornerRadius = new CornerRadius(4),
            HorizontalContentAlignment = HorizontalAlignment.Stretch,
            VerticalContentAlignment = VerticalAlignment.Stretch,
            Background = (Brush)Application.Current.Resources["CardBackgroundFillColorDefaultBrush"],
        };
        AutomationProperties.SetAutomationId(tile, $"bucket-tile:{index}");
        AutomationProperties.SetName(tile, name);
        ToolTipService.SetToolTip(tile, name);
        tile.Click += (_, _) => _open(index);
        // a medium of the caller's own bucket moved into another of theirs (online): the right-click menu, as Explorer's
        if (s.curates && !_offline())
        {
            var menu = new MenuFlyout();
            var targets = s.rows.Where(r => r.canManage && r.bucketId != s.selected).ToList();
            if (targets.Count == 0) menu.Items.Add(new MenuFlyoutItem { Text = Words.MemoryBucketsMoveEmpty(), IsEnabled = false });
            else
            {
                var move = new MenuFlyoutSubItem { Text = Words.MemoryBucketsMoveMedia(), Icon = new FontIcon { Glyph = "\uE8DE" } };
                AutomationProperties.SetAutomationId(move, "bucket-move");
                foreach (var target in targets)
                {
                    var item = new MenuFlyoutItem { Text = RowWords(target).Trim() };
                    AutomationProperties.SetAutomationId(item, $"bucket-move:{Said.Piece(target.label)}");
                    var to = target.bucketId;
                    var id = row.mediaId;
                    item.Click += async (_, _) =>
                    {
                        if (_id is not { } buckets) return;
                        try
                        {
                            await Write(new Call.BucketsMove { buckets = buckets, mediaIds = [id], target = to }, moving: true);
                        }
                        catch (Exception e)
                        {
                            Log.Write($"Buckets.Move failed ({e.GetType().Name})");
                        }
                    };
                    move.Items.Add(item);
                }
                menu.Items.Add(move);
            }
            tile.ContextFlyout = menu;
        }
        return tile;
    }

    /// <summary>A tile's thumbnail: held, else read (Gallery.Tile) and decoded in memory; the kind's glyph where it does not decode.</summary>
    private async void Thumb(Image image, FontIcon glyph, GalleryRow row, string blob)
    {
        if (_thumbs.Get(blob) is { } held)
        {
            image.Source = held;
            glyph.Visibility = Visibility.Collapsed;
            return;
        }
        try
        {
            var (answer, bytes) = await Core.CallAsync(new Call.GalleryTile { gallery = _gallery, mediaId = row.mediaId });
            if (_closed || answer is not Answer.Bytes || bytes is null) return;
            var source = await MemoryPage.Decoded(bytes, TileEdge);
            if (_closed) return;
            _thumbs.Put(blob, source);
            image.Source = source;
            glyph.Visibility = Visibility.Collapsed;
        }
        catch (Exception e)
        {
            Log.Write($"a contribution's tile was not drawn ({e.GetType().Name})");
        }
    }

    public void Dispose()
    {
        if (_closed) return;
        _closed = true;
        _watch?.Dispose();
        if (_id is { } buckets) Act.Send(new Call.BucketsClose { buckets = buckets });
    }
}
