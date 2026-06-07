import type { Scene } from './lib/types';

// 静态场景目录 —— 驱动仪表板卡片以及发送到后端的场景元数据。
export const SCENES: Scene[] = [
  {
    id: 'interview',
    name: 'Job Interview',
    description: 'Practice common interview questions in English',
    emoji: '🎯',
  },
  {
    id: 'travel',
    name: 'Travel & Tourism',
    description: 'Role-play at airports, hotels, restaurants',
    emoji: '✈️',
  },
  {
    id: 'daily_chat',
    name: 'Daily Conversation',
    description: 'Casual small talk and everyday topics',
    emoji: '☕',
  },
  {
    id: 'business',
    name: 'Business English',
    description: 'Meetings, emails, negotiations',
    emoji: '💼',
  },
];
