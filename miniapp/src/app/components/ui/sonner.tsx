"use client";

import { useEffect, useState } from 'react';
import { useStore } from '@/lib/store';
import { useTaskUi } from '@/lib/task-ui';
import { useTheme } from "next-themes";
import { Toaster as Sonner, ToasterProps, toast } from "sonner";

const Toaster = ({ ...props }: ToasterProps) => {
  const { theme = "system" } = useTheme();

  const userId = useStore(state => state.user?.id);
  const [mobile, setMobile] = useState(() => window.matchMedia('(max-width: 1023px)').matches);
  useEffect(() => {
    const media = window.matchMedia('(max-width: 1023px)');
    const update = () => setMobile(media.matches);
    media.addEventListener('change', update);
    return () => media.removeEventListener('change', update);
  }, []);
  useEffect(() => {
    toast.dismiss();
    useTaskUi.getState().inspectTask(null);
    useTaskUi.getState().closeCapture();
  }, [userId]);
  return (
    <Sonner
      theme={theme as ToasterProps["theme"]}
      className="toaster group munin-notifications"
      position={mobile ? 'bottom-center' : 'top-center'}
      offset={{ top: 'var(--notice-top, 72px)', bottom: 'var(--notice-bottom, 24px)' }}
      mobileOffset={{ top: 'var(--notice-top, 72px)', bottom: 'var(--notice-bottom, 24px)' }}
      containerAriaLabel="Уведомления"
      closeButton
      duration={5000}
      visibleToasts={1}
      expand
      toastOptions={{ closeButtonAriaLabel: 'Закрыть уведомление' }}
      style={
        {
          "--width": "var(--notice-width, min(440px, calc(100vw - 32px)))",
          "--normal-bg": "var(--popover)",
          "--normal-text": "var(--popover-foreground)",
          "--normal-border": "var(--border)",
        } as React.CSSProperties
      }
      {...props}
    />
  );
};

export { Toaster };
