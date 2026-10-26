import { useState, type FormEvent } from "react";
import { api, type ReconItem, type ReconRun } from "../api";
import { Badge, Banner, messageOf, useLoad, when } from "../ui";

export default function Recon() {
  const runs = useLoad(() => api<ReconRun[]>("ledger", "/recon/runs"));
  const [selected, setSelected] = useState<string | null>(null);
  const items = useLoad(() => (selected ? api<ReconItem[]>("ledger", `/recon/runs/${selected}/items`) : Promise.resolve([])), [selected]);
  const [error, setError] = useState<string | null>(null);

  const upload = async (e: FormEvent<HTMLFormElement>) => {
    e.preventDefault();
    const form = e.currentTarget;
    const data = new FormData(form);
    const query = new URLSearchParams({
      periodStart: new Date(String(data.get("start"))).toISOString(),
      periodEnd: new Date(String(data.get("end"))).toISOString(),
    });
    const body = new FormData();
    body.set("file", data.get("file") as File);
    try {
      const run = await api<ReconRun>("ledger", `/recon/runs?${query}`, { method: "POST", body });
      setError(null);
      setSelected(run.id);
      runs.reload();
    } catch (err) {
      setError(messageOf(err));
    }
  };

  return (
    <section>
      <h2>Reconciliation</h2>
      <Banner error={error ?? runs.error} />
      <form className="card upload" onSubmit={upload}>
        <h3>New run</h3>
        <label>Bank settlement file (CSV)<input name="file" type="file" accept=".csv" required /></label>
        <label>Period start<input name="start" type="datetime-local" required /></label>
        <label>Period end<input name="end" type="datetime-local" required /></label>
        <button type="submit">Reconcile</button>
      </form>
      <table>
        <thead>
          <tr><th>Started</th><th>File</th><th>Status</th><th>Matched</th><th>Missing in ledger</th><th>Missing in bank</th><th>Amount</th><th>Duplicate</th><th /></tr>
        </thead>
        <tbody>
          {runs.data?.map((r) => (
            <tr key={r.id} className={r.id === selected ? "selected" : ""}>
              <td>{when(r.startedAt)}</td>
              <td>{r.fileName}</td>
              <td><Badge value={r.status} /></td>
              <td className="num">{r.matched}</td>
              <td className="num">{r.missingInLedger}</td>
              <td className="num">{r.missingInBank}</td>
              <td className="num">{r.amountMismatch}</td>
              <td className="num">{r.duplicateInBank}</td>
              <td><button onClick={() => setSelected(r.id)}>Mismatches</button></td>
            </tr>
          ))}
        </tbody>
      </table>
      {runs.data?.length === 0 && <p className="muted">No runs yet.</p>}
      {selected && (
        <>
          <h3>Mismatches of the selected run</h3>
          <Banner error={items.error} />
          <table aria-label="Mismatches">
            <thead>
              <tr><th>Reference</th><th>Outcome</th><th>Ledger (minor)</th><th>Bank (minor)</th><th>Detail</th></tr>
            </thead>
            <tbody>
              {items.data?.map((i) => (
                <tr key={i.externalRef}>
                  <td>{i.externalRef}</td>
                  <td><Badge value={i.outcome} /></td>
                  <td className="num">{i.ledgerAmountMinor ?? ""}</td>
                  <td className="num">{i.bankAmountMinor ?? ""}</td>
                  <td>{i.detail}</td>
                </tr>
              ))}
            </tbody>
          </table>
          {items.data?.length === 0 && <p className="muted">No mismatches.</p>}
        </>
      )}
    </section>
  );
}
