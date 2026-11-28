using System;
using System.Threading;
using Microsoft.VisualStudio.TestTools.UnitTesting;
using Veyq.Bridge.Wire;

namespace Veyq.Bridge.Tests;

/// <summary>
/// An update's handler that throws is told to <see cref="Core.Faulted"/> before the exception goes on (inbox #1429): through
/// WinUI's SynchronizationContext it reached no UnhandledException and ended the app with nothing in the log. No DLL.
/// </summary>
[TestClass]
public sealed class SinkTest
{
    [TestMethod]
    public void AHandlersExceptionIsToldThenGoesOn()
    {
        var thrown = new InvalidOperationException("the handler's");
        Exception? told = null;
        Core.Faulted = e => told = e;
        try
        {
            var sink = new Core.Sink(null, _ => throw thrown, _ => { });
            var went = Assert.ThrowsExactly<InvalidOperationException>(() => sink.Deliver(new Update.Session { gate = new Gate.Starting() }, null));
            Assert.AreSame(thrown, went, "the exception goes on as it was");
            Assert.AreSame(thrown, told, "the exception was not told to Faulted");

            // posted to a context, as WinUI's is: told there, on the context's own run
            Exception? posted = null;
            var context = new Inline(e => posted = e);
            told = null;
            new Core.Sink(context, _ => throw thrown, _ => { }).Deliver(new Update.Session { gate = new Gate.Starting() }, null);
            Assert.AreSame(thrown, told, "a posted handler's exception was not told");
            Assert.AreSame(thrown, posted, "a posted handler's exception was kept from its context");
        }
        finally
        {
            Core.Faulted = null;
        }
    }

    /// <summary>Runs a posted callback at once, handing what it throws to <paramref name="threw"/>.</summary>
    private sealed class Inline(Action<Exception> threw) : SynchronizationContext
    {
        public override void Post(SendOrPostCallback d, object? state)
        {
            try
            {
                d(state);
            }
            catch (Exception e)
            {
                threw(e);
            }
        }
    }
}
