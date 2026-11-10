// Shapes of the gateway's JSON responses, as the pages use them.
export type Role = 'ADMIN' | 'CUSTOMER';
export type TransferStatus = 'PENDING' | 'COMPLETED' | 'FAILED';

export interface Customer {
  id: string;
  fullName: string;
  email: string;
  role: Role;
  status: 'ACTIVE' | 'LOCKED';
  createdAt: string;
}

export interface Session {
  accessToken: string;
  expiresAt: string;
  customer: Customer;
}

export interface Account {
  id: string;
  accountNo: string;
  currency: string;
  balance: number;
  customerId: string;
  createdAt: string;
}

export interface Entry {
  id: string;
  direction: 'CREDIT' | 'DEBIT';
  amount: number;
  transferId: string;
  createdAt: string;
}

export interface Transfer {
  id: string;
  sourceAccountId: string;
  destAccountId: string;
  sourceAccountNo?: string | null;
  destAccountNo?: string | null;
  amount: number;
  currency: string;
  status: TransferStatus;
  failureReason?: string | null;
  createdAt: string;
}

export interface Notification {
  id: string;
  title: string;
  content: string;
  transferId?: string | null;
  channel: 'SMS' | 'EMAIL';
  recipient?: string | null;
  createdAt: string;
}

export type Tone = 'ok' | 'bad';
export interface Message { tone: Tone; text: string }
