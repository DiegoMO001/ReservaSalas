package servidor;

import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public final class Locks {
    public final ReentrantReadWriteLock contas = new ReentrantReadWriteLock(true);
    public final ReentrantLock cadastro = new ReentrantLock(true);
    public final ReentrantLock login = new ReentrantLock(true);
}
