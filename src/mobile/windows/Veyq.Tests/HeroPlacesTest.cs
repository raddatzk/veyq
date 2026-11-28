using System.Collections.Generic;
using Microsoft.VisualStudio.TestTools.UnitTesting;
using Veyq.Core;

namespace Veyq.Tests;

/// <summary>
/// The hero leaves where it was placed last, as HeroPlaces remembers it (inbox #1429): a cold start into a folder drew the
/// level twice before a layout pass, the hero's Parent still null, and the second column's add threw. Lists stand for the
/// columns and slots.
/// </summary>
[TestClass]
public sealed class HeroPlacesTest
{
    [TestMethod]
    public void AHeroLeavesItsLastPlaceBeforeItIsAddedToTheNext()
    {
        var hero = new object();
        var first = new List<object>();
        var second = new List<object>();
        HeroPlaces.Moving(hero, () => first.Remove(hero));
        first.Add(hero);
        HeroPlaces.Moving(hero, () => second.Remove(hero));
        Assert.IsEmpty(first, "the first column still holds the hero");
        second.Add(hero);
        HeroPlaces.Leave(hero);
        Assert.IsEmpty(second, "a leave did not take the hero out");
        // left once: a second leave asks nothing of the place it already left
        second.Add(hero);
        HeroPlaces.Leave(hero);
        Assert.HasCount(1, second);
    }

    [TestMethod]
    public void AHeroPlacedNowhereLeavesNothing()
    {
        HeroPlaces.Leave(new object());
    }
}
