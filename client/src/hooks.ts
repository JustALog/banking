import { useCallback, useEffect, useRef, useState, useSyncExternalStore } from 'react';
import { get, getSession, onSessionChange } from './gateway.ts';
import type { Account } from './types.ts';

export const useSession = () => useSyncExternalStore(onSessionChange, getSession);

export interface Load<T> {
  data: T | null;
  error: Error | null;
  loading: boolean;
  reload: () => Promise<void>;
}

// Loads data on mount and whenever deps change; `reload` re-runs it without a spinner flash.
export function useLoad<T>(fn: () => Promise<T>, deps: unknown[] = []): Load<T> {
  const [state, setState] = useState<Omit<Load<T>, 'reload'>>({ data: null, error: null, loading: true });
  // eslint-disable-next-line react-hooks/exhaustive-deps
  const run = useCallback(fn, deps);
  const reload = useCallback(async () => {
    try {
      const data = await run();
      setState({ data, error: null, loading: false });
    } catch (error) {
      setState((s) => ({ data: s.data, error: error as Error, loading: false }));
    }
  }, [run]);
  useEffect(() => {
    let live = true;
    setState((s) => ({ ...s, loading: true }));
    run().then(
      (data) => live && setState({ data, error: null, loading: false }),
      (error) => live && setState({ data: null, error, loading: false }),
    );
    return () => { live = false; };
  }, [run]);
  return { ...state, reload };
}

export interface Paged<T> extends Load<T[]> {
  items: T[];
  more: boolean;
  loadMore: () => Promise<void>;
}

// Server-paged list (?page=&size=): `data` stays null until page 0 arrives, `reload` refetches page 0 and replaces items.
export function usePaged<T>(path: string, size = 50): Paged<T> {
  const [state, setState] = useState<{ items: T[]; loaded: boolean; more: boolean; error: Error | null; loading: boolean }>(
    { items: [], loaded: false, more: false, error: null, loading: true });
  const page = useRef(0);
  const seq = useRef(0); // drops responses from a superseded path or reload
  const load = useCallback(async (n: number) => {
    const id = ++seq.current;
    const [base, query = ''] = path.split('?');
    const params = new URLSearchParams(query);
    params.set('page', String(n));
    params.set('size', String(size));
    try {
      const rows = await get<T[]>(`${base}?${params}`)();
      if (id !== seq.current) return;
      page.current = n;
      setState((s) => ({ items: n ? [...s.items, ...rows] : rows, loaded: true, more: rows.length === size, error: null, loading: false }));
    } catch (error) {
      if (id === seq.current) setState((s) => ({ ...s, error: error as Error, loading: false }));
    }
  }, [path, size]);
  useEffect(() => {
    page.current = 0;
    setState({ items: [], loaded: false, more: false, error: null, loading: true }); // new query: never pair old rows with it
    load(0);
    return () => { seq.current++; };
  }, [load]);
  return {
    items: state.items,
    data: state.loaded ? state.items : null,
    more: state.more,
    error: state.error,
    loading: state.loading,
    reload: () => load(0),
    loadMore: () => { setState((s) => ({ ...s, loading: true })); return load(page.current + 1); },
  };
}

export function useDebounced<T>(value: T, ms = 300): T {
  const [v, setV] = useState(value);
  useEffect(() => {
    const t = setTimeout(() => setV(value), ms);
    return () => clearTimeout(t);
  }, [value, ms]);
  return v;
}

export const useAccounts = () => useLoad(get<Account[]>('/accounts'));
