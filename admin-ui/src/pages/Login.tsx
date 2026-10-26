import { useState, type FormEvent } from "react";
import { useAuth } from "../auth";
import { Banner, messageOf } from "../ui";

export default function Login() {
  const { login } = useAuth();
  const [error, setError] = useState<string | null>(null);

  const submit = async (e: FormEvent<HTMLFormElement>) => {
    e.preventDefault();
    const form = new FormData(e.currentTarget);
    try {
      await login(String(form.get("username")), String(form.get("password")));
    } catch (err) {
      setError(messageOf(err));
    }
  };

  return (
    <form className="card login" onSubmit={submit}>
      <h1>PayLedger admin</h1>
      <Banner error={error} />
      <label>
        Username
        <input name="username" autoComplete="username" required />
      </label>
      <label>
        Password
        <input name="password" type="password" autoComplete="current-password" required />
      </label>
      <button type="submit">Sign in</button>
    </form>
  );
}
