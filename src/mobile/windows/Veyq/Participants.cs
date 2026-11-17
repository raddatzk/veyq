// A Memory's participants on Windows (ADR 0144 decision 1; Linux's curation.rs, Apple's people line): the people line
// beside the facts line — one person as their picture and name, several as up to three overlapping PersonPictures with
// "+N" in the third slot (the core's PeopleShown) — a Button whose Flyout lists everyone (a former member greyed, a
// current one's row opening their profile, Shell.OpenPerson); "Edit participants" beside it where whoever looks may edit,
// and the "Add participants" chip while nobody is listed. The picker is a ContentDialog, Fluent's way of asking for a few
// fields over a page: a search box over the scope's current members as CheckBoxes, the ids no member holds any more above
// them greyed, Save and Cancel. Every rule is the core's (Participants.*, Watched.Participants).
using System;
using System.Collections.Generic;
using System.Linq;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Veyq.Bridge;
using Veyq.Bridge.Wire;

namespace Veyq;

internal static class PeopleLine
{
    /// <summary>The people line of gallery <paramref name="gallery"/> as <paramref name="p"/> shows it; null where there
    /// is nothing to draw.</summary>
    public static UIElement? Line(Window window, long gallery, PeopleShown p)
    {
        var line = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8 };
        AutomationProperties.SetAutomationId(line, "memory-people");
        if (p.people.Count == 0)
        {
            if (!p.add) return null;
            var add = new Button { Content = Chip("", Words.MemoryParticipantsAdd()) };
            AutomationProperties.SetAutomationId(add, "participants-add");
            add.Click += (_, _) => ParticipantsPicker.Open(window, gallery);
            line.Children.Add(add);
            return line;
        }
        var people = new Button { Padding = new Thickness(6, 3, 10, 3), Background = new SolidColorBrush(Microsoft.UI.Colors.Transparent), BorderThickness = new Thickness(0) };
        AutomationProperties.SetAutomationId(people, "participants");
        var names = string.Join(", ", p.people.Select(x => CurationText.Name(x.name, x.former)).Where(n => n.Length > 0));
        AutomationProperties.SetName(people, $"{Words.MemoryParticipantsTitle()}: {names}");
        ToolTipService.SetToolTip(people, Words.MemoryParticipantsTitle());
        people.Content = Faces(p);
        var flyout = new Flyout();
        // the list built per open: a control reused under a new parent after its popup closed is the trap of Dialogs.cs
        flyout.Opening += (_, _) => flyout.Content = List(p, flyout);
        people.Flyout = flyout;
        line.Children.Add(people);
        if (p.edits)
        {
            var edit = new HyperlinkButton { Content = Words.MemoryParticipantsEdit(), VerticalAlignment = VerticalAlignment.Center };
            AutomationProperties.SetAutomationId(edit, "participants-edit");
            edit.Click += (_, _) => ParticipantsPicker.Open(window, gallery);
            line.Children.Add(edit);
        }
        return line;
    }

    private static StackPanel Chip(string glyph, string text)
    {
        var content = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 6 };
        content.Children.Add(new FontIcon { Glyph = glyph, FontSize = 14 });
        content.Children.Add(new TextBlock { Text = text });
        return content;
    }

    /// <summary>One person as their picture and name; several as up to three overlapping pictures, "+N" in the third slot.</summary>
    private static UIElement Faces(PeopleShown p)
    {
        var faces = new StackPanel { Orientation = Orientation.Horizontal, VerticalAlignment = VerticalAlignment.Center };
        if (p.people.Count == 1)
        {
            var one = p.people[0];
            var name = CurationText.Name(one.name, one.former);
            faces.Spacing = 8;
            faces.Children.Add(Avatars.Person(name, one.userId, 28));
            var label = new TextBlock { Text = name, VerticalAlignment = VerticalAlignment.Center };
            if (one.former) label.Foreground = (Brush)Application.Current.Resources["TextFillColorSecondaryBrush"];
            faces.Children.Add(label);
            return faces;
        }
        var first = true;
        foreach (var slot in CurationText.Slots(p))
        {
            FrameworkElement face;
            if (slot.UserId is { } id)
            {
                var who = p.people.FirstOrDefault(x => x.userId == id);
                face = Avatars.Person(CurationText.Name(who?.name, who?.former ?? false), id, 28);
            }
            else
            {
                face = new Border
                {
                    Width = 28,
                    Height = 28,
                    CornerRadius = new CornerRadius(14),
                    Background = (Brush)Application.Current.Resources["ControlAltFillColorTertiaryBrush"],
                    Child = new TextBlock { Text = "+" + slot.More.ToString(System.Globalization.CultureInfo.CurrentCulture), FontSize = 11, HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center },
                };
            }
            // overlapping as a group of faces is drawn: each over the last by a third
            if (!first) face.Margin = new Thickness(-9, 0, 0, 0);
            first = false;
            faces.Children.Add(face);
        }
        return faces;
    }

    /// <summary>Everyone listed: a current member's row opens their profile, a former member's is greyed and opens nothing.</summary>
    private static UIElement List(PeopleShown p, Flyout flyout)
    {
        var column = new StackPanel { Spacing = 2, MinWidth = 220 };
        var heading = new TextBlock { Text = Words.MemoryParticipantsTitle(), Style = (Style)Application.Current.Resources["BodyStrongTextBlockStyle"], Margin = new Thickness(0, 0, 0, 6) };
        AutomationProperties.SetHeadingLevel(heading, Microsoft.UI.Xaml.Automation.Peers.AutomationHeadingLevel.Level2);
        column.Children.Add(heading);
        foreach (var person in p.people)
        {
            var name = CurationText.Name(person.name, person.former);
            var row = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 10 };
            row.Children.Add(Avatars.Person(name, person.userId, 32));
            var label = new TextBlock { Text = name, VerticalAlignment = VerticalAlignment.Center };
            if (!person.opensProfile) label.Foreground = (Brush)Application.Current.Resources["TextFillColorSecondaryBrush"];
            row.Children.Add(label);
            if (person.opensProfile)
            {
                var open = new Button
                {
                    Content = row,
                    HorizontalAlignment = HorizontalAlignment.Stretch,
                    HorizontalContentAlignment = HorizontalAlignment.Left,
                    Background = new SolidColorBrush(Microsoft.UI.Colors.Transparent),
                    BorderThickness = new Thickness(0),
                    Padding = new Thickness(4),
                };
                AutomationProperties.SetAutomationId(open, $"participant:{person.userId}");
                AutomationProperties.SetName(open, Words.PeopleOpen(name));
                var id = person.userId;
                open.Click += (_, _) =>
                {
                    flyout.Hide();
                    Log.Write("participants: a profile opened");
                    Act.Send(new Call.ShellOpenPerson { userId = id });
                };
                column.Children.Add(open);
            }
            else
            {
                row.Padding = new Thickness(4);
                AutomationProperties.SetAutomationId(row, $"participant:{person.userId}");
                AutomationProperties.SetName(row, name);
                column.Children.Add(row);
            }
        }
        return new ScrollViewer { Content = column, MaxHeight = 360 };
    }
}

/// <summary>The participants picker (core's ParticipantsPicker, the web's MemoryParticipantsDialog.vue).</summary>
internal sealed class ParticipantsPicker
{
    private readonly Window _window;
    private readonly long _id;
    private readonly TextBox _search = new() { PlaceholderText = Words.MemoryParticipantsSearch(), MinWidth = 340 };
    private readonly StackPanel _rows = new() { Spacing = 2 };
    private readonly TextBlock _status = new() { TextWrapping = TextWrapping.Wrap, Visibility = Visibility.Collapsed };
    private readonly Button _retry = new() { Content = Words.MemoryPeopleRetry(), Visibility = Visibility.Collapsed };
    private readonly TextBlock _full = new() { TextWrapping = TextWrapping.Wrap, Visibility = Visibility.Collapsed };
    private readonly TextBlock _problem = new() { TextWrapping = TextWrapping.Wrap, Visibility = Visibility.Collapsed };
    private readonly ProgressRing _ring = new() { IsActive = true, Width = 24, Height = 24 };
    private readonly ContentDialog _dialog;
    /// <summary>The rows drawn by user id, and the ids they were drawn for (a row is rebuilt only when the list changes).</summary>
    private readonly Dictionary<string, CheckBox> _boxes = [];
    private string _drawnIds = "";
    private string? _set;
    private bool _drawing;
    private Watch? _watch;

    /// <summary>Gallery <paramref name="gallery"/>'s participants picker: opened in the core (Participants.Open) and shown.</summary>
    public static async void Open(Window window, long gallery)
    {
        if (Dialogs.Open) return;
        try
        {
            var (answer, _) = await Core.CallAsync(new Call.ParticipantsOpen { gallery = gallery });
            if (answer is not Answer.ParticipantsOpened { participants: var id })
            {
                Log.Write($"Participants.Open answered {(answer is Answer.Refused r ? $"Refused {r.why}" : answer.GetType().Name)}");
                return;
            }
            Log.Write($"participants picker {id} open");
            await new ParticipantsPicker(window, id).Show();
        }
        catch (Exception e)
        {
            Log.Write($"the participants picker failed ({e.GetType().Name})");
        }
    }

    private ParticipantsPicker(Window window, long id)
    {
        _window = window;
        _id = id;
        var content = new StackPanel { Spacing = 10, MinWidth = 360 };
        content.Children.Add(new TextBlock { Text = Words.MemoryParticipantsDialogHint(), TextWrapping = TextWrapping.Wrap, Foreground = (Brush)Application.Current.Resources["TextFillColorSecondaryBrush"] });
        AutomationProperties.SetAutomationId(_search, "participants-search");
        AutomationProperties.SetName(_search, Words.MemoryParticipantsSearch());
        _search.TextChanged += (_, _) =>
        {
            if (_drawing) return;
            if (_set is { } set && set == _search.Text)
            {
                _set = null;
                return;
            }
            _set = null;
            Act.Send(new Call.ParticipantsSearch { participants = _id, query = _search.Text });
        };
        content.Children.Add(_search);
        content.Children.Add(_ring);
        AutomationProperties.SetAutomationId(_rows, "participants-members");
        content.Children.Add(new ScrollViewer { Content = _rows, MaxHeight = 320, VerticalScrollBarVisibility = ScrollBarVisibility.Auto });
        AutomationProperties.SetAutomationId(_status, "participants-status");
        content.Children.Add(_status);
        AutomationProperties.SetAutomationId(_retry, "participants-retry");
        _retry.Click += (_, _) => Act.Send(new Call.ParticipantsLoad { participants = _id });
        content.Children.Add(_retry);
        AutomationProperties.SetAutomationId(_full, "participants-full");
        _full.Text = Words.MemoryParticipantsFull(100);
        content.Children.Add(_full);
        _problem.Foreground = (Brush)Application.Current.Resources["SystemFillColorCriticalBrush"];
        AutomationProperties.SetAutomationId(_problem, "participants-problem");
        content.Children.Add(_problem);
        _dialog = new ContentDialog
        {
            Title = Words.MemoryParticipantsDialogTitle(),
            Content = content,
            PrimaryButtonText = Words.EntrySave(),
            CloseButtonText = Words.EntryCancel(),
            DefaultButton = ContentDialogButton.Primary,
            IsPrimaryButtonEnabled = false,
        };
        AutomationProperties.SetAutomationId(_dialog, "participants-dialog");
        _dialog.PrimaryButtonClick += async (_, args) =>
        {
            // the dialog stays until the core answered: a failure is said in it
            args.Cancel = true;
            var deferral = args.GetDeferral();
            try
            {
                var (answer, _) = await Core.CallAsync(new Call.ParticipantsSave { participants = _id });
                Log.Write($"Participants.Save: {(answer is Answer.FactsSaved f ? f.outcome.ToString() : answer.GetType().Name)}");
                if (answer is Answer.FactsSaved { outcome: FactsSave.Saved }) _dialog.Hide();
            }
            catch (Exception e)
            {
                Log.Write($"Participants.Save failed ({e.GetType().Name})");
            }
            finally
            {
                deferral.Complete();
            }
        };
        _watch = Core.Watch(new Watched.Participants { participants = id }, Updated, e => Log.Write($"a participants picker update: {e.Message}"));
    }

    private async System.Threading.Tasks.Task Show()
    {
        await Dialogs.Show(_window, _dialog);
        _watch?.Dispose();
        Act.Send(new Call.ParticipantsClose { participants = _id });
        Log.Write($"participants picker {_id} closed");
    }

    private void Updated(Update update)
    {
        if (update is not Update.Participants { shown: var s }) return;
        if (s is null)
        {
            _dialog.Hide();
            return;
        }
        Log.Write($"participants shown: {s.members?.Count ?? -1} members ({s.members?.Count(m => m.ticked) ?? 0} ticked), {s.former.Count} former, failed {s.failed}, full {s.full}, no matches {s.noMatches}, nobody {s.nobody}, save {s.canSave}, saving {s.saving}, said {s.said?.ToString() ?? "-"}, saved {s.saved}");
        _drawing = true;
        try
        {
            if (_search.Text != s.query && _search.FocusState == FocusState.Unfocused)
            {
                _set = s.query;
                _search.Text = s.query;
            }
            _ring.Visibility = s.members is null && !s.failed ? Visibility.Visible : Visibility.Collapsed;
            var rows = s.former.Select(m => (m, true)).Concat((s.members ?? []).Select(m => (m, false))).ToList();
            var ids = string.Join(",", rows.Select(r => r.m.userId));
            if (ids != _drawnIds)
            {
                _drawnIds = ids;
                _rows.Children.Clear();
                _boxes.Clear();
                foreach (var (m, former) in rows) _rows.Children.Add(Row(m, former));
            }
            foreach (var (m, _) in rows)
            {
                if (!_boxes.TryGetValue(m.userId, out var box)) continue;
                box.IsChecked = m.ticked;
                box.IsEnabled = m.togglable && !s.saving;
            }
            var status = s.failed ? Words.MemoryParticipantsLoadError() : s.nobody ? Words.MemoryParticipantsNoMembers() : s.noMatches ? Words.MemoryParticipantsNoMatches() : null;
            _status.Text = status ?? "";
            _status.Visibility = status is null ? Visibility.Collapsed : Visibility.Visible;
            _retry.Visibility = s.failed ? Visibility.Visible : Visibility.Collapsed;
            _full.Visibility = s.full ? Visibility.Visible : Visibility.Collapsed;
            var said = CurationText.ParticipantsSaid(s.said);
            _problem.Text = said ?? "";
            _problem.Visibility = said is null ? Visibility.Collapsed : Visibility.Visible;
            _dialog.IsPrimaryButtonEnabled = s.canSave && !s.saving;
            if (s.saved) _dialog.Hide();
        }
        finally
        {
            _drawing = false;
        }
    }

    /// <summary>A member's row: their picture and name in a CheckBox; a former member's greyed, still untickable.</summary>
    private CheckBox Row(MemberShown m, bool former)
    {
        var name = CurationText.Name(m.name, former);
        var face = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8 };
        face.Children.Add(Avatars.Person(name, m.userId, 24));
        var label = new TextBlock { Text = name, VerticalAlignment = VerticalAlignment.Center };
        if (former) label.Foreground = (Brush)Application.Current.Resources["TextFillColorSecondaryBrush"];
        face.Children.Add(label);
        var box = new CheckBox { Content = face, IsChecked = m.ticked };
        AutomationProperties.SetAutomationId(box, $"member:{m.userId}");
        AutomationProperties.SetName(box, name);
        var id = m.userId;
        async void Toggle()
        {
            if (_drawing) return;
            try
            {
                var (answer, _) = await Core.CallAsync(new Call.ParticipantsToggle { participants = _id, userId = id });
                Log.Write($"participants: a tick changed: {(answer is Answer.Refused r ? $"Refused {r.why}" : answer.GetType().Name)}");
                if (answer is Answer.Done) return;
                // refused (past the cap): the box shows what the pick holds, which did not change
                _drawing = true;
                box.IsChecked = !box.IsChecked;
                _drawing = false;
            }
            catch (Exception e)
            {
                Log.Write($"Participants.Toggle failed ({e.GetType().Name})");
            }
        }
        box.Checked += (_, _) => Toggle();
        box.Unchecked += (_, _) => Toggle();
        _boxes[m.userId] = box;
        return box;
    }
}
