import { useState } from "react";
import { api, post } from "../api";
import { Banner, messageOf } from "../ui";

export default function Operations() {
  const [result, setResult] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const replay = async () => {
    if (!window.confirm("Replay every message in the dead-letter topic?")) return;
    try {
      const { replayed } = await api<{ replayed: number }>("ledger", "/admin/dlt/replay", post);
      setError(null);
      setResult(`Replayed ${replayed} message${replayed === 1 ? "" : "s"}`);
    } catch (e) {
      setResult(null);
      setError(messageOf(e));
    }
  };

  return (
    <section>
      <h2>Operations</h2>
      <Banner error={error} />
      <div className="card">
        <h3>Dead-letter replay</h3>
        <p className="muted">Sends the messages in payments.events.DLT back to the ledger consumer. Safe to repeat: the consumer ignores events it already processed.</p>
        <button onClick={replay}>Replay dead letters</button>
        {result && <p role="status">{result}</p>}
      </div>
    </section>
  );
}
