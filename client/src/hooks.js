import { useCallback, useEffect, useState, useSyncExternalStore } from 'react';
import { get, getSession, onSessionChange } from './gateway.js';

export const useSession = () => useSyncExternalStore(onSessionChange, getSession);

// Loads data on mount and whenever deps change; `reload` re-runs it without a spinner flash.
export function useLoad(fn, deps = []) {
  const [state, setState] = useState({ data: null, error: null, loading: true });
  // eslint-disable-next-line react-hooks/exhaustive-deps
  const run = useCallback(fn, deps);
  const reload = useCallback(async () => {
    try {
      const data = await run();
      setState({ data, error: null, loading: false });
    } catch (error) {
      setState((s) => ({ data: s.data, error, loading: false }));
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

export const useAccounts = () => useLoad(get('/accounts'));
