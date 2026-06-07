import { Link } from 'react-router-dom';
import Button from '../components/Button';

// NotFoundPage —— 最简 404 页面，带有返回首页的行动按钮。
export default function NotFoundPage() {
  return (
    <div className="flex-1 flex flex-col items-center justify-center text-center px-4 py-16">
      <p className="text-sm font-semibold text-primary uppercase tracking-wider mb-2">404</p>
      <h1 className="text-3xl font-semibold text-on-surface mb-2">Page not found</h1>
      <p className="text-base text-on-surface-variant mb-6">
        The page you’re looking for doesn’t exist.
      </p>
      <Link to="/dashboard">
        <Button>Back to home</Button>
      </Link>
    </div>
  );
}
