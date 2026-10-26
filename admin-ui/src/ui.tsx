import { useCallback, useEffect, useState } from "react";
import { ApiError } from "./api";

export const messageOf = (e: unknown): string =>
  e instanceof ApiError ? (e.retryAfter ? `${e.message} (retry in ${e.retryAfter}s)` : e.message) : "Something went wrong";

export function Banner({ error }: { error: string | null }) {
  return error ? (
    <div className="banner" role="alert">
      {error}
    </div>
  ) : null;
}

// Loads data once and on demand; shows the error instead of throwing.
export function useLoad<T>(load: () => Promise<T>, deps: unknown[] = []) {
  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState<string | null>(null);
  const reload = useCallback(() => {
    load().then(setData, (e) => setError(messageOf(e)));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, deps);
  useEffect(() => {
    setError(null);
    reload();
  }, [reload]);
  return { data, error, reload, setError };
}

export const when = (iso: string | null) => (iso ? new Date(iso).toLocaleString() : "");
// Display only: amounts arrive as decimals from the API and are never calculated with here.
export const money = (amount: number, currency: string) => `${amount.toFixed(2)} ${currency}`;

export function Badge({ value }: { value: string }) {
  return <span className={`badge badge-${value.toLowerCase()}`}>{value}</span>;
}
