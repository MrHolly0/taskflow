import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
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

  return useMutation({
    mutationFn: async (request: CreateTaskRequest) => {
      const response = await getClient().post<Task>('/tasks/quick', request);
      return response.data;
    },
    onSuccess: (task) => {
      queryClient.setQueryData<Task[]>(['tasks', 'list'], previous => previous ? [task, ...previous] : previous);
      queryClient.invalidateQueries({ queryKey: ['tasks'] });
      queryClient.invalidateQueries({ queryKey: ['groups'] });
    },
  });
};

interface TasksPage {
  content: Task[];
  totalElements: number;
}

export const useTasksList = () => {
  return useQuery({
    queryKey: ['tasks', 'list'],
    queryFn: async () => {
      const tasks: Task[] = [];
      let page = 0;
      let total = Infinity;
      while (tasks.length < total) {
        const response = await getClient().get<TasksPage>('/tasks', {
          params: { page, size: 100, sort: 'createdAt,desc' },
        });
        tasks.push(...response.data.content);
        total = response.data.totalElements;
        if (!response.data.content.length) break;
        page++;
      }
      return tasks;
    },
    staleTime: 1000 * 60 * 2,
  });
};

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
      });
      return response.data;
    },
    onSuccess: (task) => {
      queryClient.setQueryData<Task[]>(['tasks', 'list'], (previous) => previous?.map(item => item.id === task.id ? task : item));
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
