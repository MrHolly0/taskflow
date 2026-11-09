import { useMemo, useRef } from 'react';
import { useQuery, useInfiniteQuery, useMutation, useQueryClient, keepPreviousData, type InfiniteData } from '@tanstack/react-query';
import { getAuthenticatedClient as getClient } from '@/lib/authenticated-client';

export interface ReminderResponse {
  id: string;
  fireAt: string;
  status: string;
  persistent: boolean;
}

// daysOfWeek — имена значений java.time.DayOfWeek ("MONDAY".."SUNDAY"), как
// их отдаёт Jackson по умолчанию, а не числа ISO-8601.
export interface RecurrenceRule {
  type: string;
  intervalN?: number;
  daysOfWeek?: string[];
  dayOfMonth?: number;
  endsAt?: string;
}

export interface TaskResponse {
  id: string;
  title: string;
  description?: string;
  priority: string;
  status: string;
  deadline?: string;
  plannedDate?: string;
  estimateMinutes?: number;
  source: string;
  groupId?: string;
  groupName?: string;
  tags: string[];
  createdAt: string;
  updatedAt: string;
  completedAt?: string;
  startedAt?: string;
  plannedDateSetAt?: string;
  recurrence?: RecurrenceRule;
  reminders?: ReminderResponse[];
  persistentReminder?: boolean;
}

type Task = TaskResponse;

interface FocusResponse {
  tasks: Task[];
}

interface FocusHintResponse {
  hint?: string;
}

interface PlannedDateSuggestionResponse {
  plannedDate?: string;
}

interface DigestResponse {
  topTasks: Task[];
  totalTasks: number;
  completedToday: number;
  overdueTasks: number;
}

export interface TaskStatsItem {
  createdAt: string;
  completedAt?: string | null;
  deadline?: string | null;
  status: string;
}

interface TaskStatsResponse {
  tasks: TaskStatsItem[];
}

export const useFocusTasks = (availableMinutes?: number) => {
  return useQuery({
    queryKey: ['tasks', 'focus', availableMinutes ?? null],
    queryFn: async () => {
      const response = await getClient().get<FocusResponse>('/tasks/focus', {
        params: availableMinutes ? { availableMinutes } : undefined,
      });
      return response.data.tasks;
    },
    staleTime: 1000 * 60 * 2, // 2 min
  });
};

export const useUpcomingFocusTasks = (enabled: boolean, availableMinutes?: number) => {
  return useQuery({
    queryKey: ['tasks', 'focus', 'upcoming', availableMinutes ?? null],
    queryFn: async () => {
      const response = await getClient().get<FocusResponse>('/tasks/focus/upcoming', {
        params: availableMinutes ? { availableMinutes } : undefined,
      });
      return response.data.tasks;
    },
    enabled,
    staleTime: 1000 * 60 * 2,
  });
};

export const useFocusHint = (taskId?: string, enabled = false) => {
  return useQuery({
    queryKey: ['tasks', taskId, 'focus-hint'],
    queryFn: async () => {
      const response = await getClient().get<FocusHintResponse>(`/tasks/${taskId}/focus-hint`);
      return response.data.hint;
    },
    enabled: enabled && Boolean(taskId),
    staleTime: Infinity,
    retry: false,
  });
};

export const usePlannedDateSuggestion = (taskId?: string, enabled = false) => {
  return useQuery({
    queryKey: ['tasks', taskId, 'planned-date-suggestion'],
    queryFn: async () => {
      const response = await getClient().post<PlannedDateSuggestionResponse>(
        `/tasks/${taskId}/planned-date-suggestion`,
      );
      return response.data.plannedDate;
    },
    enabled: enabled && Boolean(taskId),
    staleTime: Infinity,
    retry: false,
  });
};

export const useDigestTasks = (date?: string) => {
  return useQuery({
    queryKey: ['tasks', 'digest', date || 'today'],
    queryFn: async () => {
      const params = date ? { date } : {};
      const response = await getClient().get<DigestResponse>('/tasks/digest', { params });
      return response.data;
    },
    staleTime: 1000 * 60 * 5, // 5 min
  });
};

export const useCompleteTask = () => {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (taskId: string) => {
      await getClient().post(`/tasks/${taskId}/complete`);
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['tasks'] });
    },
  });
};

export const useDeleteTask = () => {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (taskId: string) => {
      await getClient().delete(`/tasks/${taskId}`);
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['tasks'] });
    },
  });
};

export const useAllTasks = (date?: string) => {
  return useQuery({
    queryKey: ['tasks', 'all', date],
    queryFn: async () => {
      const params = date ? { date } : {};
      const response = await getClient().get<DigestResponse>('/tasks/digest', { params });
      return response.data.topTasks;
    },
    staleTime: 1000 * 60 * 2,
  });
};

export interface CreateTaskRequest {
  skipDefaultReminders?: boolean;
  reminderAt?: string;

  title: string;
  description?: string;
  priority: string;
  deadline?: string;
  plannedDate?: string;
  estimateMinutes?: number;
  groupId?: string;
  groupName?: string;
  tags?: string[];
  persistentReminder?: boolean;
}

export interface GroupResponse {
  id: string;
  name: string;
  color?: string;
  icon?: string;
}

export interface CreateGroupRequest {
  name: string;
  color?: string;
  icon?: string;
}

export const useCreateTask = () => {
  const queryClient = useQueryClient();
  const pending = useRef<{ body: string; key: string } | null>(null);
  return useMutation({
    mutationFn: async (request: CreateTaskRequest) => {
      const body = JSON.stringify(request);
      if (!pending.current || pending.current.body !== body) pending.current = { body, key: crypto.randomUUID() };
      const response = await getClient().post<Task>('/tasks/quick', request, {
        headers: { 'Idempotency-Key': pending.current.key },
      });
      return response.data;
    },
    onSuccess: () => {
      pending.current = null;
      queryClient.invalidateQueries({ queryKey: ['tasks'] });
      queryClient.invalidateQueries({ queryKey: ['groups'] });
    },
  });
};

export interface TasksPage {
  content: Task[];
  totalElements: number;
  number: number;
  size: number;
}
export interface TaskListFilters {
  status?: string;
  group?: string;
  query?: string;
  context?: string;
  order?: string;
  completedSince?: string;
  completedUntil?: string;
  revealIds?: string[];
  size?: number;
}
export interface TaskOverview {
  active: number;
  history: number;
  statuses: Record<string, number>;
  groups: { groupId: string; active: number; history: number; weeklyDone: number }[];
}
const listParams = (filters: TaskListFilters) => ({
  ...filters, revealIds: filters.revealIds?.join(',') || undefined,
});
export const useTaskPage = (filters: TaskListFilters, page = 0, enabled = true) => useQuery({
  queryKey: ['tasks', 'page', filters, page],
  queryFn: async ({ signal }) => (await getClient().get<TasksPage>('/tasks/view', {
    params: { ...listParams(filters), page }, signal,
  })).data,
  staleTime: 120000,
  enabled,
});
export const useTaskOverview = () => useQuery({
  queryKey: ['tasks', 'overview'],
  queryFn: async ({ signal }) => (await getClient().get<TaskOverview>('/tasks/overview', { signal })).data,
  staleTime: 120000,
});
export const useTasksList = (filters: TaskListFilters = {}, enabled = true) => {
  const result = useInfiniteQuery({
    queryKey: ['tasks', 'list', filters],
    initialPageParam: 0,
    placeholderData: keepPreviousData,
    queryFn: async ({ pageParam, signal }) => (await getClient().get<TasksPage>('/tasks/view', {
      params: { ...listParams(filters), page: pageParam, size: filters.size ?? 24 }, signal,
    })).data,
    getNextPageParam: (last) => (last.number + 1) * last.size < last.totalElements ? last.number + 1 : undefined,
    staleTime: 120000,
    enabled,
  });
  const data = useMemo(() => result.data ? [...new Map(result.data.pages.flatMap(page => page.content).map(task => [task.id, task])).values()] : undefined, [result.data]);
  return { ...result, data,
    total: result.data?.pages[0]?.totalElements ?? 0 };
};

export function updateTaskLists(queryClient: ReturnType<typeof useQueryClient>, task: Task) {
  queryClient.setQueriesData<InfiniteData<TasksPage>>({ queryKey: ['tasks', 'list'] }, previous => previous ? {
    ...previous, pages: previous.pages.map(page => ({ ...page,
      content: page.content.map(item => item.id === task.id ? task : item),
    })),
  } : previous);
}

export const useTaskStats = () => {
  return useQuery({
    queryKey: ['tasks', 'stats'],
    queryFn: async () => {
      const response = await getClient().get<TaskStatsResponse>('/tasks/stats');
      return response.data.tasks;
    },
    staleTime: 1000 * 60 * 2,
  });
};

export interface UpdateTaskRequest {
  id: string;
  title?: string;
  description?: string;
  priority?: string;
  status?: string;
  deadline?: string | null;
  plannedDate?: string | null;
  estimateMinutes?: number | null;
  groupId?: string | null;
  clearGroup?: boolean;
  clearPlannedDate?: boolean;
  clearDeadline?: boolean;
  clearEstimateMinutes?: boolean;
  recurrence?: RecurrenceRule | null;
  persistentReminder?: boolean | null;
}

export const useUpdateTask = () => {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async ({ id, ...patch }: UpdateTaskRequest) => {
      const response = await getClient().patch<Task>(`/tasks/${id}`, {
        ...patch,
        ...(patch.groupId === null ? { clearGroup: true } : {}),
        ...(patch.plannedDate === null ? { clearPlannedDate: true } : {}),
        ...(patch.deadline === null ? { clearDeadline: true } : {}),
        ...(patch.estimateMinutes === null ? { clearEstimateMinutes: true } : {}),
      });
      return response.data;
    },
    onSuccess: (task) => {
      updateTaskLists(queryClient, task);
      queryClient.invalidateQueries({ queryKey: ['tasks'] });
    },
  });
};

export const useGroups = () => {
  return useQuery({
    queryKey: ['groups'],
    queryFn: async () => {
      const response = await getClient().get<GroupResponse[]>('/groups');
      return response.data;
    },
    staleTime: 1000 * 60 * 5,
  });
};

export const useCreateGroup = () => {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (request: CreateGroupRequest) => {
      const response = await getClient().post<GroupResponse>('/groups', request);
      return response.data;
    },
    onSuccess: (group) => {
      queryClient.setQueryData<GroupResponse[]>(['groups'], previous => previous ? [...previous, group] : previous);
      queryClient.invalidateQueries({ queryKey: ['groups'] });
    },
  });
};

export const useDeleteGroup = () => {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (groupId: string) => {
      await getClient().delete(`/groups/${groupId}`);
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['groups'] });
      queryClient.invalidateQueries({ queryKey: ['tasks'] });
    },
  });
};

export const useUpdateGroup = () => {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async ({ id, ...request }: CreateGroupRequest & { id: string }) => {
      const response = await getClient().put<GroupResponse>(`/groups/${id}`, request);
      return response.data;
    },
    onSuccess: (group) => {
      queryClient.setQueryData<GroupResponse[]>(['groups'], previous => previous?.map(item => item.id === group.id ? group : item));
      queryClient.invalidateQueries({ queryKey: ['groups'] });
      queryClient.invalidateQueries({ queryKey: ['tasks'] });
    },
  });
};

export const useTaskReminders = (taskId: string | undefined, enabled: boolean) => {
  return useQuery({
    queryKey: ['tasks', taskId, 'reminders'],
    queryFn: async () => {
      const response = await getClient().get<ReminderResponse[]>(`/tasks/${taskId}/reminders`);
      return response.data;
    },
    enabled: enabled && !!taskId,
    refetchInterval: enabled ? 30000 : false,
  });
};

export const useAddReminder = () => {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async ({ taskId, fireAt }: { taskId: string; fireAt: string }) => {
      await getClient().post(`/tasks/${taskId}/reminders`, { fireAt });
    },
    onSuccess: (_data, { taskId }) => {
      queryClient.invalidateQueries({ queryKey: ['tasks', taskId, 'reminders'] });
      queryClient.invalidateQueries({ queryKey: ['tasks'] });
    },
  });
};

export const useCancelReminder = () => {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async ({ taskId, reminderId }: { taskId: string; reminderId: string }) => {
      await getClient().delete(`/tasks/${taskId}/reminders/${reminderId}`);
    },
    onSuccess: (_data, { taskId }) => {
      queryClient.invalidateQueries({ queryKey: ['tasks', taskId, 'reminders'] });
      queryClient.invalidateQueries({ queryKey: ['tasks'] });
    },
  });
};

// Б2: точка решения по настойчивому повтору — «отложить на срок», а не
// снять совсем (useCancelReminder). Автоматический запас повторов не тратит.
export const useSnoozeReminder = () => {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async ({ taskId, reminderId, fireAt }: { taskId: string; reminderId: string; fireAt: string }) => {
      await getClient().post(`/tasks/${taskId}/reminders/${reminderId}/snooze`, { fireAt });
    },
    onSuccess: (_data, { taskId }) => {
      queryClient.invalidateQueries({ queryKey: ['tasks', taskId, 'reminders'] });
      queryClient.invalidateQueries({ queryKey: ['tasks'] });
    },
  });
};

export const useClearRecurrence = () => {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (taskId: string) => {
      await getClient().delete(`/tasks/${taskId}/recurrence`);
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['tasks'] });
    },
  });
};
