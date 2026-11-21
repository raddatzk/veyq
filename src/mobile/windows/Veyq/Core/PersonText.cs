// What a person's profile's frame says on Windows without a widget (ADR 0144 decision 11; core's PersonProfile). Pure, so
// Veyq.Tests reads it.

namespace Veyq.Core;

using Veyq.Bridge.Wire;

internal static class PersonText
{
    /// <summary>A person's page's title (core's PersonProfile): their name, else the former member's or the unknown
    /// member's words.</summary>
    public static string Title(PersonShown p) => p.name ?? (p.former ? Words.MemoryParticipantsFormer() : Words.PeopleUnknownMember());
}
