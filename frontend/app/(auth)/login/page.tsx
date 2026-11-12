import { AuthForm } from '@/components/AuthForm';

export default async function LoginPage({ searchParams }: { searchParams: Promise<{ reset?: string }> }) {
  const { reset } = await searchParams;
  return (
    <AuthForm mode="login"
      notice={reset === 'success' ? 'Your password has been reset. Please sign in with your new password.' : undefined} />
  );
}
