import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom';
import { AppShell, RequireRole } from './components.jsx';
import { Landing, Login, NotFound, Register } from './pages/public.jsx';
import { AccountDetail, Accounts, Dashboard, Notifications, Profile, TransactionDetail, Transactions, Transfer } from './pages/user.jsx';
import { AdminAccounts, AdminCustomers, AdminTransfers } from './pages/admin.jsx';
import './styles.css';

createRoot(document.getElementById('root')).render(
  <StrictMode>
    <BrowserRouter>
      <Routes>
        <Route path="/" element={<Landing />} />
        <Route path="/login" element={<Login />} />
        <Route path="/register" element={<Register />} />
        <Route element={<RequireRole />}>
          <Route element={<AppShell />}>
            <Route path="/app" element={<Dashboard />} />
            <Route path="/app/accounts" element={<Accounts />} />
            <Route path="/app/accounts/:id" element={<AccountDetail />} />
            <Route path="/app/transfer" element={<Transfer />} />
            <Route path="/app/transactions" element={<Transactions />} />
            <Route path="/app/transactions/:id" element={<TransactionDetail />} />
            <Route path="/app/notifications" element={<Notifications />} />
            <Route path="/app/profile" element={<Profile />} />
          </Route>
        </Route>
        <Route element={<RequireRole role="ADMIN" />}>
          <Route element={<AppShell />}>
            <Route path="/admin" element={<AdminCustomers />} />
            <Route path="/admin/accounts" element={<AdminAccounts />} />
            <Route path="/admin/transfers" element={<AdminTransfers />} />
          </Route>
        </Route>
        <Route path="/home" element={<Navigate to="/" replace />} />
        <Route path="*" element={<NotFound />} />
      </Routes>
    </BrowserRouter>
  </StrictMode>,
);
