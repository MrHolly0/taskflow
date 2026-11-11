import { useRef } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { getAuthenticatedClient as getClient } from '@/lib/authenticated-client';

export type ProposalStatus = 'PENDING' | 'APPLIED' | 'PARTIALLY_APPLIED' | 'REJECTED' | 'EXPIRED' | 'FAILED' | 'DECLINED';

export interface ProposedAction {
  ordinal: number;
  type: string;
  targetTaskId?: string;
  payload: Record<string, unknown>;
  summary: string;
  accepted: boolean;
}

export interface Proposal {
  id: string | null;
  shortCode: string | null;
  userId: string;
  status: ProposalStatus;
  sourceText: string;
  clarification?: string;
  actions: ProposedAction[];
  createdAt: string;
  expiresAt: string;
  exclusive: boolean;
  ambiguityReason?: string;
  rejections: string[];
}

export interface ActionOutcome {
  ordinal: number;
  summary: string;
  success: boolean;
  error?: string;
  taskId?: string;
  undoId?: string;
  undoExpiresAt?: string;
  undone?: boolean;
}

export interface ApplyResult {
  status: ProposalStatus;
  appliedCount: number;
  totalCount: number;
  outcomes: ActionOutcome[];
}

export interface QuickResult {
  proposal: Proposal;
  applied: ApplyResult | null;
}

export const useSendAssistantMessage = () => {
  const queryClient=useQueryClient();
  const requestKey = useRef<{ text?: string; file?: File; key: string } | null>(null);
  return useMutation({
    mutationFn: async ({ text, file }: { text?: string; file?: File }) => {
      if (!requestKey.current || requestKey.current.text !== text || requestKey.current.file !== file) {
        requestKey.current = { text, file, key: crypto.randomUUID() };
      }
      const formData = new FormData();
      if (text) formData.append('text', text);
      if (file) formData.append('file', file);
      const response = await getClient().post<QuickResult>('/assistant/turn', formData, {
        headers: { 'Content-Type': 'multipart/form-data', 'Idempotency-Key': requestKey.current.key },
        timeout: 190000,
      });
      return response.data;
    },
    onSuccess: result => {
      requestKey.current=null;
      if(result.applied) {
        void queryClient.invalidateQueries({queryKey:['tasks']});
        void queryClient.invalidateQueries({queryKey:['groups']});
      }
    },
  });
};

export const useQuickAdd = () => {
  const queryClient = useQueryClient();
  const requestKey = useRef<{ text?: string; file?: File; key: string } | null>(null);
  return useMutation({
    mutationFn: async ({ text, file }: { text?: string; file?: File }) => {
      if (!requestKey.current || requestKey.current.text !== text || requestKey.current.file !== file) {
        requestKey.current = { text, file, key: crypto.randomUUID() };
      }
      const formData = new FormData();
      if (text) formData.append('text', text);
      if (file) formData.append('file', file);
      const response = await getClient().post<QuickResult>('/assistant/capture', formData, {
        headers: { 'Content-Type': 'multipart/form-data', 'Idempotency-Key': requestKey.current.key },
        timeout: 190000,
      });
      return response.data;
    },
    onSuccess: (result) => {
      requestKey.current = null;
      if (result.applied) {
        queryClient.invalidateQueries({ queryKey: ['tasks'] });
        queryClient.invalidateQueries({ queryKey: ['groups'] });
      }
    },
  });
};

export const useSetActionAccepted = () => {
  return useMutation({
    mutationFn: async ({ proposalId, ordinal, accepted }: { proposalId: string; ordinal: number; accepted: boolean }) => {
      const response = await getClient().patch<Proposal>(
        `/assistant/proposals/${proposalId}/actions/${ordinal}`,
        { accepted }
      );
      return response.data;
    },
  });
};

export const useUpdateActionReminder = () => {
  return useMutation({
    mutationFn: async ({ proposalId, ordinal, reminderAt }: { proposalId: string; ordinal: number; reminderAt: string | null }) => {
      const response = await getClient().patch<Proposal>(
        `/assistant/proposals/${proposalId}/actions/${ordinal}/reminder`,
        { reminderAt }
      );
      return response.data;
    },
  });
};

export const useUpdateActionPlannedDate = () => {
  return useMutation({
    mutationFn: async ({ proposalId, ordinal, plannedDate }: { proposalId: string; ordinal: number; plannedDate: string | null }) => {
      const response = await getClient().patch<Proposal>(
        `/assistant/proposals/${proposalId}/actions/${ordinal}/planned-date`,
        { plannedDate }
      );
      return response.data;
    },
  });
};

export const useSelectAlternative = () => {
  return useMutation({
    mutationFn: async ({ proposalId, ordinal }: { proposalId: string; ordinal: number }) => {
      const response = await getClient().post<Proposal>(
        `/assistant/proposals/${proposalId}/actions/${ordinal}/select`
      );
      return response.data;
    },
  });
};

export const useApplyProposal = () => {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (proposalId: string) => {
      const response = await getClient().post<ApplyResult>(`/assistant/proposals/${proposalId}/apply`);
      return response.data;
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['tasks'] });
      queryClient.invalidateQueries({ queryKey: ['groups'] });
    },
  });
};

export const useRejectProposal = () => {
  return useMutation({
    mutationFn: async (proposalId: string) => {
      await getClient().post(`/assistant/proposals/${proposalId}/reject`);
    },
  });
};

export const useUndoAssistantAction=()=> {
  const queryClient=useQueryClient();
  return useMutation({
    mutationFn:async(id:string)=> {
      const response=await getClient().post<{taskId:string;kind:string;undone:boolean}>(`/assistant/operations/${id}/undo`);
      return response.data;
    },
    onSuccess:()=> {
      void queryClient.invalidateQueries({queryKey:['tasks']});
      void queryClient.invalidateQueries({queryKey:['groups']});
    },
  });
};
