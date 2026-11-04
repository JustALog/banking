// Response shapes the console reads (see API_LIST.md). UUIDs and Instants are strings; money is minor units.

export type Role = 'USER' | 'ADMIN';

export interface Customer {
  id: string;
  email: string;
  fullName: string;
  status: 'ACTIVE' | 'LOCKED';
  role: Role;
  createdAt: string;
}

export interface LoginResponse {
  accessToken: string;
  expiresAt: string;
  customer: Customer;
}

export interface Account {
  id: string;
  accountNo: string;
  customerId: string;
  currency: string;
  balance: number;
  createdAt: string;
}

export interface LedgerEntry {
  id: string;
  transferId: string;
  accountId: string;
  direction: 'DEBIT' | 'CREDIT';
  amount: number;
  createdAt: string;
}

export interface Transfer {
  id: string;
  sourceAccountId: string;
  destAccountId: string;
  amount: number;
  currency: string;
  status: 'PENDING' | 'COMPLETED' | 'FAILED';
  failureReason: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface Notification {
  id: string;
  title: string;
  content: string;
  channel: 'EMAIL' | 'SMS';
  createdAt: string;
}

export interface ErrorBody {
  detail?: string;
  error?: string;
  errors?: string[];
}
