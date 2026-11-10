// One action of the core whose answer the screen does not wait for (Linux's bridge::send): the core answers with a
// state the screen already watches; anything but Done is said in the log by its kind.
using System;
using Veyq.Bridge;
using Veyq.Bridge.Wire;

namespace Veyq;

internal static class Act
{
    public static async void Send(Call call)
    {
        try
        {
            var answer = await Core.CallAsync(call);
            if (answer is not Answer.Done) Log.Write($"{call.GetType().Name} answered {answer.GetType().Name}");
        }
        catch (UnreadableException e)
        {
            Log.Write($"{call.GetType().Name}: {e.Message}");
        }
        catch (Exception e)
        {
            // an exception out of an async void would end the app in combase, with nothing in the log
            Log.Write($"{call.GetType().Name} failed: {e.GetType().Name}");
        }
    }
}
