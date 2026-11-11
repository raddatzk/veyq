using System.Runtime.InteropServices;
using Microsoft.VisualStudio.TestTools.UnitTesting;
using Veyq.Bridge.Wire;

namespace Veyq.Bridge.Tests;

/// <summary> ADR 0128 decision 1: the payload a callback is handed beside its JSON is read once, in the generated layer
/// (<c>Native.Payload</c>), and copied before the callback returns — the core's buffer may be gone right after. NULL is
/// no payload; an empty one is still one (an empty range). Runs on any host: no veyq.dll is loaded. </summary>
[TestClass]
public sealed unsafe class PayloadTest
{
    [TestMethod]
    public void ThePayloadIsCopiedBeforeTheCallbackReturns()
    {
        byte[] picture = [0x89, (byte)'P', (byte)'N', (byte)'G', 0, 255];
        var core = Marshal.AllocHGlobal(picture.Length);
        byte[]? copied;
        try
        {
            Marshal.Copy(picture, 0, core, picture.Length);
            copied = Native.Payload((byte*)core, (nuint)picture.Length);
            // the core reuses its buffer once the callback returned
            new System.Span<byte>((void*)core, picture.Length).Clear();
        }
        finally
        {
            Marshal.FreeHGlobal(core);
        }
        CollectionAssert.AreEqual(picture, copied);

        byte one = 7;
        CollectionAssert.AreEqual(System.Array.Empty<byte>(), Native.Payload(&one, 0));
        Assert.IsNull(Native.Payload(null, 0));
    }
}
