import AudioPlayer from './AudioPlayer';

export type ChatBubbleRole = 'user' | 'assistant' | 'system';

interface ChatBubbleProps {
  role: ChatBubbleRole;
  text: string;
  timestamp?: string;
  audioUrl?: string;
  imageUrl?: string;
  /** Optional badge: "Shadowing practice" when the user was practicing the model's answer. */
  practiceMode?: 'shadowing';
  /** Optional badge: "Uploading and analyzing..." while the audio is still being processed. */
  pending?: boolean;
}

function Avatar({ icon, tone }: { icon: string; tone: 'ai' | 'user' }) {
  return (
    <div
      className={`w-10 h-10 rounded-full flex items-center justify-center flex-shrink-0 ${
        tone === 'ai' ? 'bg-surface-high text-primary' : 'bg-primary-100 text-primary-800'
      }`}
    >
      <span
        className="material-symbols-outlined"
        style={{ fontVariationSettings: tone === 'ai' ? "'FILL' 1" : undefined }}
      >
        {icon}
      </span>
    </div>
  );
}

export default function ChatBubble({
  role,
  text,
  audioUrl,
  imageUrl,
  practiceMode,
  pending,
}: ChatBubbleProps) {
  const isUser = role === 'user';
  const avatarTone = isUser ? 'user' : 'ai';
  const avatarIcon = isUser ? 'person' : 'smart_toy';

  return (
    <div
      className={`flex items-start gap-3 max-w-[85%] ${isUser ? 'ml-auto justify-end' : ''}`}
    >
      {!isUser && <Avatar icon={avatarIcon} tone={avatarTone} />}
      <div
        className={
          isUser
            ? 'bg-primary px-4 py-3 rounded-2xl rounded-tr-none shadow-md text-white'
            : 'bg-surface-low border border-outline-variant/20 px-4 py-3 rounded-2xl rounded-tl-none shadow-sm'
        }
      >
        {imageUrl && (
          <img
            src={imageUrl}
            alt=""
            className="mb-2 max-h-48 rounded-lg border border-outline-variant/20"
          />
        )}
        {text && (
          <p className="text-base leading-7">{text}</p>
        )}
        {practiceMode === 'shadowing' && (
          <p
            className={`mt-1 text-xs font-semibold ${
              isUser ? 'text-white/80' : 'text-on-surface-variant'
            }`}
          >
            Shadowing practice
          </p>
        )}
        {pending && (
          <p
            className={`mt-1 text-xs font-semibold ${
              isUser ? 'text-white/80' : 'text-on-surface-variant'
            }`}
          >
            Uploading and analyzing...
          </p>
        )}
        {audioUrl && <AudioPlayer src={audioUrl} />}
      </div>
      {isUser && <Avatar icon={avatarIcon} tone={avatarTone} />}
    </div>
  );
}
