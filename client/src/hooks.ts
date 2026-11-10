import { useCallback, useEffect, useState, useSyncExternalStore } from 'react';
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

export const useAccounts = () => useLoad(get<Account[]>('/accounts'));
