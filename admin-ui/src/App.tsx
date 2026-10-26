import { BrowserRouter, Navigate, NavLink, Route, Routes } from "react-router-dom";
import { AuthProvider, useAuth } from "./auth";
import Accounts from "./pages/Accounts";
import Login from "./pages/Login";
import Operations from "./pages/Operations";
import { PaymentDetail, PaymentList } from "./pages/Payments";
import Recon from "./pages/Recon";

function Shell() {
  const { session, logout } = useAuth();
  if (!session) return <Login />;
  const admin = session.role === "ADMIN";
  return (
    <>
      <header>
        <strong>PayLedger</strong>
        <nav>
          {admin && <NavLink to="/accounts">Accounts</NavLink>}
          <NavLink to="/payments">Payments</NavLink>
          {admin && <NavLink to="/recon">Reconciliation</NavLink>}
          {admin && <NavLink to="/operations">Operations</NavLink>}
        </nav>
        <span className="who">
          {session.username} ({session.role}) <button onClick={logout}>Sign out</button>
        </span>
      </header>
      <main>
        <Routes>
          {admin && <Route path="/accounts" element={<Accounts />} />}
          <Route path="/payments" element={<PaymentList />} />
          <Route path="/payments/:id" element={<PaymentDetail />} />
          {admin && <Route path="/recon" element={<Recon />} />}
          {admin && <Route path="/operations" element={<Operations />} />}
          <Route path="*" element={<Navigate to={admin ? "/accounts" : "/payments"} replace />} />
        </Routes>
      </main>
    </>
  );
}

export default function App() {
  return (
    <AuthProvider>
      <BrowserRouter>
        <Shell />
      </BrowserRouter>
    </AuthProvider>
  );
}
