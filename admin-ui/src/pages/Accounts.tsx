import { useState } from "react";
import { api, post, type Account } from "../api";
import { Badge, Banner, messageOf, money, useLoad, when } from "../ui";

const TYPES = ["", "USER_WALLET", "MERCHANT", "SETTLEMENT", "FEES", "SUSPENSE"];

export default function Accounts() {
  const [owner, setOwner] = useState("");
  const [type, setType] = useState("");
  const [page, setPage] = useState(0);
  const query = new URLSearchParams({ page: String(page), size: "25" });
  if (owner) query.set("ownerId", owner);
  if (type) query.set("type", type);
  const { data, error, reload, setError } = useLoad(() => api<Account[]>("ledger", `/accounts?${query}`), [owner, type, page]);

  const toggle = async (a: Account) => {
    const action = a.status === "ACTIVE" ? "freeze" : "unfreeze";
    if (!window.confirm(`${action} account of ${a.ownerId}?`)) return;
    try {
      await api("ledger", `/accounts/${a.id}/${action}`, post);
      reload();
    } catch (e) {
      setError(messageOf(e));
    }
  };

  return (
    <section>
      <h2>Accounts</h2>
      <Banner error={error} />
      <div className="filters">
        <input aria-label="Owner" placeholder="Owner (exact)" value={owner} onChange={(e) => { setPage(0); setOwner(e.target.value); }} />
        <select aria-label="Type" value={type} onChange={(e) => { setPage(0); setType(e.target.value); }}>
          {TYPES.map((t) => (
            <option key={t} value={t}>{t || "All types"}</option>
          ))}
        </select>
      </div>
      <table>
        <thead>
          <tr><th>Owner</th><th>Type</th><th>Balance</th><th>Status</th><th>Created</th><th /></tr>
        </thead>
        <tbody>
          {data?.map((a) => (
            <tr key={a.id}>
              <td>{a.ownerId}</td>
              <td>{a.type}</td>
              <td className="num">{money(a.balance, a.currency)}</td>
              <td><Badge value={a.status} /></td>
              <td>{when(a.createdAt)}</td>
              <td><button onClick={() => toggle(a)}>{a.status === "ACTIVE" ? "Freeze" : "Unfreeze"}</button></td>
            </tr>
          ))}
        </tbody>
      </table>
      {data?.length === 0 && <p className="muted">No accounts.</p>}
      <div className="pager">
        <button disabled={page === 0} onClick={() => setPage(page - 1)}>Previous</button>
        <span>Page {page + 1}</span>
        <button disabled={(data?.length ?? 0) < 25} onClick={() => setPage(page + 1)}>Next</button>
      </div>
    </section>
  );
}
