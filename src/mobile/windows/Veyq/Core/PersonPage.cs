// A person's profile on Windows (ADR 0144 decisions 11–14; Linux's person_page.rs, the web's PersonProfile.vue): a page of
// the shell while its state names a person (Shell.person), back the shell's. Core's frame — their picture at its largest,
// their name (the former or unknown member's words), "~username" under a real name — then the safety number (ADR 0148
// decision 3, SafetyNumberSection: one's own to show, another member's state and Compare; one's own page has Edit profile
// in the shell's CommandBar) — then each application's section the registry holds (Memories' "Memories with {name}",
// PersonMemoriesSection), in the order the composition root registered them. Every state is the core's (Person.*,
// Watched.Person). Nothing about it is written to disk.

namespace Veyq.Core;

using System;
using System.Collections.Generic;
using System.Linq;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Veyq.Bridge;
using Veyq.Bridge.Wire;

internal sealed class PersonPage : IDisposable
{
    private readonly Window _window;
    private readonly ScrollViewer _scroll;
    private readonly StackPanel _column = new() { Spacing = 12, Padding = new Thickness(24, 12, 24, 32) };
    private readonly PersonPicture _picture = new() { Width = 96, Height = 96, HorizontalAlignment = HorizontalAlignment.Left };
    private readonly TextBlock _name = new() { Style = (Style)Application.Current.Resources["TitleTextBlockStyle"], TextWrapping = TextWrapping.Wrap };
    private readonly TextBlock _username = new() { Style = (Style)Application.Current.Resources["BodyTextBlockStyle"], Visibility = Visibility.Collapsed };
    private readonly TextBlock _hint = new() { TextWrapping = TextWrapping.Wrap, Visibility = Visibility.Collapsed };
    private long? _id;
    private Watch? _watch;
    private PersonShown? _shown;
    private bool _closed;

    public string UserId { get; }

    /// <summary>One's own page (ShellShown.me): Edit profile in the CommandBar, one's own safety number to show.</summary>
    public bool Mine { get; }

    /// <summary>The safety number below the name (ADR 0148 decision 3), made once the person is known to be a member.</summary>
    private SafetyNumberSection? _safety;
    private readonly ContentControl _safetyHolder = new() { HorizontalContentAlignment = HorizontalAlignment.Stretch };

    /// <summary>Each application's section of the profile (the registry's PersonSection), under the frame.</summary>
    private readonly List<PersonSection> _sections = new();

    public UIElement Page => _scroll;

    public PersonPage(Window window, string userId, bool mine)
    {
        _window = window;
        UserId = userId;
        Mine = mine;
        AutomationProperties.SetAutomationId(_picture, "person-picture");
        _column.Children.Add(_picture);
        AutomationProperties.SetAutomationId(_name, "person-name");
        AutomationProperties.SetHeadingLevel(_name, Microsoft.UI.Xaml.Automation.Peers.AutomationHeadingLevel.Level1);
        _column.Children.Add(_name);
        _username.Foreground = (Brush)Application.Current.Resources["TextFillColorSecondaryBrush"];
        AutomationProperties.SetAutomationId(_username, "person-username");
        _column.Children.Add(_username);
        _hint.Foreground = (Brush)Application.Current.Resources["TextFillColorSecondaryBrush"];
        AutomationProperties.SetAutomationId(_hint, "person-hint");
        _column.Children.Add(_hint);
        _column.Children.Add(_safetyHolder);
        foreach (var app in Registry.Shared.Applications)
        {
            if (app.PersonSection is not { } section) continue;
            var made = section(window);
            _sections.Add(made);
            _column.Children.Add(made.View);
        }
        _scroll = new ScrollViewer { Content = _column, HorizontalScrollMode = ScrollMode.Disabled, HorizontalScrollBarVisibility = ScrollBarVisibility.Disabled };
        AutomationProperties.SetAutomationId(_scroll, "person-page");
        Open();
    }

    private async void Open()
    {
        try
        {
            var (answer, _) = await Core.CallAsync(new Call.PersonOpen { userId = UserId });
            if (answer is not Answer.PersonOpened { person: var person })
            {
                Log.Write($"Person.Open answered {(answer is Answer.Refused r ? $"Refused {r.why}" : answer.GetType().Name)}");
                return;
            }
            if (_closed)
            {
                Act.Send(new Call.PersonClose { person = person });
                return;
            }
            _id = person;
            Log.Write($"person page {person} open");
            _watch = Core.Watch(new Watched.Person { person = person }, Updated, e => Log.Write($"a person page update: {e.Message}"));
        }
        catch (Exception e)
        {
            Log.Write($"the person page did not open ({e.GetType().Name})");
        }
    }

    private void Updated(Update update)
    {
        if (_closed || update is not Update.Person { shown: var s }) return;
        _shown = s;
        if (s is null) return;
        var m = s.memories;
        Log.Write($"person shown: named {s.name is not null}, former {s.former}, picture {s.picture}, username {s.username is not null}, sections {string.Join(",", s.sections)}"
            + (m is null ? "" : $"; memories: loading {m.loading}, failed {m.failed}, empty {m.empty}, partial {m.partial}, views {m.offersViews} {m.view}, {m.rows.Count} rows, {m.located} located"));
        var title = PersonText.Title(s);
        _name.Text = title;
        _picture.DisplayName = title;
        Avatars.Show(_picture, s.picture ? s.userId : null);
        // the core's line already reads "~username"
        _username.Text = s.username ?? "";
        _username.Visibility = s.username is null ? Visibility.Collapsed : Visibility.Visible;
        _hint.Text = s.former ? Words.PeopleFormerMemberHint() : "";
        _hint.Visibility = s.former ? Visibility.Visible : Visibility.Collapsed;
        // a former member has no safety number to show or compare
        if (!s.former && _safety is null)
        {
            _safety = new SafetyNumberSection(_window, UserId, Mine, () => _name.Text);
            _safetyHolder.Content = _safety.View;
        }
        if (_id is { } id)
        {
            foreach (var section in _sections) section.Shown(id, s);
        }
    }

    public void Dispose()
    {
        if (_closed) return;
        _closed = true;
        _safety?.Close();
        foreach (var section in _sections) section.Dispose();
        _watch?.Dispose();
        if (_id is { } person) Act.Send(new Call.PersonClose { person = person });
    }
}
