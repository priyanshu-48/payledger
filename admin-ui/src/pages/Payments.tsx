import { useState } from "react";
import { Link, useParams } from "react-router-dom";
import { api, post, type Payment, type PaymentEvent } from "../api";
import { Badge, Banner, messageOf, money, useLoad, when } from "../ui";

const STATUSES = ["", "CREATED", "AUTHORIZATION_UNKNOWN", "AUTHORIZED", "CAPTURED", "SETTLED", "FAILED", "REFUNDED"];

export function PaymentList() {
  const [status, setStatus] = useState("");
  const [page, setPage] = useState(0);
  const query = new URLSearchParams({ page: String(page), size: "25" });
  if (status) query.set("status", status);
  const { data, error } = useLoad(() => api<Payment[]>("payments", `/payments?${query}`), [status, page]);

  return (
    <section>
      <h2>Payments</h2>
      <Banner error={error} />
      <div className="filters">
        <select aria-label="Status" value={status} onChange={(e) => { setPage(0); setStatus(e.target.value); }}>
          {STATUSES.map((s) => (
            <option key={s} value={s}>{s || "All statuses"}</option>
          ))}
        </select>
      </div>
      <table>
        <thead>
          <tr><th>Created</th><th>Merchant</th><th>Amount</th><th>Status</th><th /></tr>
        </thead>
        <tbody>
          {data?.map((p) => (
            <tr key={p.id}>
              <td>{when(p.createdAt)}</td>
              <td>{p.merchantId}</td>
              <td className="num">{money(p.amount, p.currency)}</td>
              <td><Badge value={p.status} /></td>
              <td><Link to={`/payments/${p.id}`}>Details</Link></td>
            </tr>
          ))}
        </tbody>
      </table>
      {data?.length === 0 && <p className="muted">No payments.</p>}
      <div className="pager">
        <button disabled={page === 0} onClick={() => setPage(page - 1)}>Previous</button>
        <span>Page {page + 1}</span>
        <button disabled={(data?.length ?? 0) < 25} onClick={() => setPage(page + 1)}>Next</button>
      </div>
    </section>
  );
}

export function PaymentDetail() {
  const { id } = useParams();
  const payment = useLoad(() => api<Payment>("payments", `/payments/${id}`), [id]);
  const history = useLoad(() => api<PaymentEvent[]>("payments", `/payments/${id}/events`), [id]);
  const [error, setError] = useState<string | null>(null);

  const act = async (action: "capture" | "refund") => {
    try {
      await api("payments", `/payments/${id}/${action}`, post);
      setError(null);
      payment.reload();
      history.reload();
    } catch (e) {
      setError(messageOf(e));
    }
  };

  const p = payment.data;
  return (
    <section>
      <p><Link to="/payments">All payments</Link></p>
      <h2>Payment</h2>
      <Banner error={error ?? payment.error ?? history.error} />
      {p && (
        <>
          <dl className="facts">
            <dt>Id</dt><dd>{p.id}</dd>
            <dt>Merchant</dt><dd>{p.merchantId}</dd>
            <dt>Payer account</dt><dd>{p.payerAccountId}</dd>
            <dt>Amount</dt><dd>{money(p.amount, p.currency)}</dd>
            <dt>Status</dt><dd><Badge value={p.status} /></dd>
            {p.bankRef && (<><dt>Bank reference</dt><dd>{p.bankRef}</dd></>)}
            {p.failureReason && (<><dt>Failure</dt><dd>{p.failureReason}</dd></>)}
          </dl>
          <div className="actions">
            {p.status === "AUTHORIZED" && <button onClick={() => act("capture")}>Capture</button>}
            {(p.status === "CAPTURED" || p.status === "SETTLED") && <button onClick={() => act("refund")}>Refund</button>}
          </div>
        </>
      )}
      <h3>Status history</h3>
      <ol className="timeline" aria-label="Status history">
        {history.data?.map((e, i) => (
          <li key={i}>
            <Badge value={e.to} /> {e.from ? <span className="muted">from {e.from}</span> : <span className="muted">created</span>}
            <span className="when">{when(e.at)}</span>
            {e.reason && <div className="muted">{e.reason}</div>}
          </li>
        ))}
      </ol>
    </section>
  );
}
