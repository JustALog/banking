// Shapes of the gateway's JSON responses (see API_LIST.md and the services' *Response records).
// UUIDs and Instants arrive as strings; money is a whole number of minor units.

export type Uuid = string;
export type IsoInstant = string;

export type Role = 'USER' | 'ADMIN';
export type CustomerStatus = 'ACTIVE' | 'LOCKED';

export interface Customer {
  id: Uuid;
  email: string;
  fullName: string;
  status: CustomerStatus;
  role: Role;
  createdAt: IsoInstant;
}

export interface LoginResponse {
  accessToken: string;
  tokenType: string;
  expiresAt: IsoInstant;
  customer: Customer;
}

export interface Session {
  accessToken: string;
  expiresAt: IsoInstant;
  customer: Customer;
}

export interface Account {
  id: Uuid;
  accountNo: string;
  customerId: Uuid;
  currency: string;
  balance: number;
  createdAt: IsoInstant;
}

export type Direction = 'DEBIT' | 'CREDIT';

export interface LedgerEntry {
  id: Uuid;
  transferId: Uuid;
  accountId: Uuid;
  direction: Direction;
  amount: number;
  createdAt: IsoInstant;
}

export type TransferStatus = 'PENDING' | 'COMPLETED' | 'FAILED';

export interface Transfer {
  id: Uuid;
  sourceAccountId: Uuid;
  destAccountId: Uuid;
  amount: number;
  currency: string;
  status: TransferStatus;
  failureReason: string | null;
  createdAt: IsoInstant;
  updatedAt: IsoInstant;
}

export interface Notification {
  id: Uuid;
  customerId: Uuid;
  transferId: Uuid | null;
  channel: 'EMAIL' | 'SMS';
  recipient: string;
  title: string;
  content: string;
  status: 'SENT' | 'FAILED';
  createdAt: IsoInstant;
}

/** Error bodies: Spring ProblemDetail (`detail`), validation (`errors`) or the gateway's `{ error }`. */
export interface ErrorBody {
  detail?: string;
  error?: string;
  errors?: string[];
}
