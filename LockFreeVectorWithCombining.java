import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicMarkableReference;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceArray;

public class LockFreeVectorWithCombining<T> {

	/*
	 * Based on Walulya at al.'s lock-free vector paper.
	 * 
	 * I think I can do CAS(e, u, false, false) - if a node is ever marked, then we can let the 
	 * CAS fail cuz the value doesn't matter.
	 */
	
	
	
	// wait one frigging minute - does each thread get a combining queue?
	
	
	
	static final int FBS = 2; // First bucket size; can be any power of 2.
	static final int QSize = -1; // Size of the bounded combining queue.
	AtomicReference<Descriptor<AtomicMarkableReference<T>>> desc;
	AtomicReferenceArray<AtomicReferenceArray<AtomicMarkableReference<T>>> vals;
	AtomicReference<Queue<AtomicMarkableReference<T>>> batch;
	ThreadLocal<ThreadInfo<T>> threadInfoGlobal;
	WriteDescriptor<AtomicMarkableReference<T>> SENTINEL_ONE, SENTINEL_TWO;

	public LockFreeVectorWithCombining() {
		desc = new AtomicReference<Descriptor<AtomicMarkableReference<T>>>(new Descriptor<>(0, null, null));
		// You need to do this cuz Java is dumb and won't let you make generic arrays.
		vals = new AtomicReferenceArray<AtomicReferenceArray<AtomicMarkableReference<T>>>(32);
		vals.getAndSet(0, new AtomicReferenceArray<AtomicMarkableReference<T>>(FBS));
		SENTINEL_ONE = new WriteDescriptor<AtomicMarkableReference<T>>(null, null, -1);
		SENTINEL_TWO = new WriteDescriptor<AtomicMarkableReference<T>>(null, null, -2);
	}

	void reserve(int newSize) {
		// The -1 is used because getBucket() finds the bucket for a given index. Since we're 
		// checking sizes, we only need to allocate size-1 indexes.
		
		// The index of the largest in-use bucket.
		int i = getBucket(desc.get().size - 1);
		if (i < 0) i = 0;
		
		// Add new buckets until we have enough buckets for newSize elements.
		while (i < getBucket(newSize - 1)) {
			i++;
			allocateBucket(i);
		}
	}

	void pushBack(T newElement) {
		// vector??
		boolean batchExists = false, help = false;
		Descriptor<AtomicMarkableReference<T>> currDesc, newDesc;
		ThreadInfo<T> threadInfo = threadInfoGlobal.get();
		AtomicMarkableReference<T> newRef = new AtomicMarkableReference<>(newElement, false);
		while (true) {
			currDesc = desc.get();

			completeWrite(currDesc.writeOp);
			
			// "a concurrent Combine operation is on-going, and the thread tries to help complete 
			// this operation."
			if (currDesc.batch != null) {
				combine(threadInfo, currDesc, true);
			}

			// Create a new Descriptor and WriteDescriptor.
			WriteDescriptor<AtomicMarkableReference<T>> writeOp = new WriteDescriptor<AtomicMarkableReference<T>>
					(readRefAt(currDesc.size), newRef, currDesc.size);
			newDesc = new Descriptor<AtomicMarkableReference<T>>(currDesc.size + 1, writeOp, OpType.PUSH);

			// Determine which bucket this element will go in.
			int bucketIdx = getBucket(currDesc.size);
			// If the appropriate bucket doesn't exist, create it.
			if (vals.get(bucketIdx) == null) allocateBucket(bucketIdx);

			//  If a thread has added an item to the combining queue, subsequent pushback operations 
			// will add the items to the combining queue until the queue is closed
			// ie. our CAS later in the method failed or this thread has already added stuff to a 
			// combining queue, so add this operation to the combining queue
			if (batchExists || (threadInfo.q != null && threadInfo.q == batch.get())) {
				if (addToBatch(threadInfo, writeOp)) {
					return; // the operation is in the queue, we're done here
				}
				// we couldn't add it to the batch - not sure what this does, though
				// why is newDesc.writeOp now null? we didn't add it to the queue
				newDesc = new Descriptor<AtomicMarkableReference<T>>(currDesc.size, null, null);
				help = true;
				threadInfo.q = null;
			}

			// try the normal compare and set
			if (desc.compareAndSet(currDesc, newDesc)) {
				if (newDesc.batch != null) {
					// why are we combining?
					combine(threadInfo, newDesc, true);
					if (help) { // we're going to help another thread, I guess?
						help = false;
						continue;
					}
				}
				break; // we're done
			} else {
				// the thread adds the operation to the combining queue (in the next loop iteration)
				batchExists = true;
			}
		}

		completeWrite(newDesc.writeOp);
	}

	T popBack() {
		// vector??
		boolean batchExists = false, help = false;
		Descriptor<AtomicMarkableReference<T>> currDesc, newDesc;
		ThreadInfo<T> threadInfo = threadInfoGlobal.get();
		T elem;
		while (true) {
			currDesc = desc.get();

			// complete a pending operation
			completeWrite(currDesc.writeOp);
			
			// we need to execute any waiting pushes before we can pop
			if (currDesc.batch != null) {
				combine(threadInfo, currDesc, true);
			}
			
			if (currDesc.size == 0 && batch == null) return null; // There's nothing to pop.
			elem = readAt(currDesc.size - 1);

			// Create a new Descriptor.
			newDesc = new Descriptor<>(currDesc.size - 1, null, OpType.POP);
			newDesc.batch = batch.get();
			newDesc.offset = currDesc.size;

			if (desc.compareAndSet(currDesc, newDesc)) {
				if (newDesc.batch != null && newDesc.batch == batch.get()) {
					// We're gonna help with a combine, then return the last element in the 
					// combining queue.
					threadInfo.q.closed = true;
					elem = combine(threadInfo, newDesc, false);
				} else {
					// Marking the node as logicall deleted.
					markNode(currDesc.size);
				}
				break;
			}
		}

		return elem;
	}
	
	// I call the WriteDescriptor writeOp for consistent naming (the paper calls it descr, which is 
	// confusing).
	boolean addToBatch(ThreadInfo<T> threadInfo, WriteDescriptor<AtomicMarkableReference<T>> descr) {
		Queue<AtomicMarkableReference<T>> queue = batch.get();
		if (queue == null) { // check if the vector has a combining queue
			Queue<AtomicMarkableReference<T>> newQ = new Queue<>(descr);
			if (batch.compareAndSet(queue, newQ)) {
				return true;
			}
		}
		
		queue = batch.get(); // in case a different thread CASed before us
		if (queue == null || queue.closed) {
			// we don't add descr, cuz it's closed/non-existant
			descr.batch = queue;
			return false;
		}
		
		int ticket = queue.tail.getAndAdd(1); // where we'll insert
		if (ticket >= QSize) {
			// queue is full, so close it and return a failure
			queue.closed = true;
			descr.batch = queue;
			return false;
		}
		
		if (!queue.items.compareAndSet(ticket, null, descr)) { // add it to the queue
			return false; // we failed, someone stole our spot
		}
		
		Queue<AtomicMarkableReference<T>> newQ = new Queue<>(descr); // what's the point of this? we don't do anything with it
		return true;
	}
	
	T combine(ThreadInfo<T> threadInfo, Descriptor<AtomicMarkableReference<T>> descr, boolean helper) {
		Queue<AtomicMarkableReference<T>> q = batch.get();
		int headIndex, headCount, currCount = 0;
		
		if (q == null || !q.closed) { // the paper has an AND here, which I don't think makes sense?
			return null; // queue not closed, so somebody else already combined
		}
		
		// we dequeue items and add them to the vector
		while (true) {
			// headIndex is the dequeue index
			Head head = q.head.get();
			headIndex = head.index;
			headCount = head.count;
			
			int bucketIdx = getBucket(descr.offset + currCount);
			if (vals.get(bucketIdx) == null) allocateBucket(bucketIdx);
			
			AtomicMarkableReference<T> oldValue = readRefAt(descr.offset + headCount);
			int ticket = headIndex;
			if (ticket == q.tail.get() || ticket == QSize) {
				break;
			}
			
			// linearize with push operation
			// 1) the corresponding AddToBatch operation has not completed adding item to the combining queue.
			if (q.items.compareAndSet(ticket, SENTINEL_ONE, SENTINEL_TWO)) {
				Head newHead = new Head(headIndex + 1, headCount); // update head
				q.head.compareAndSet(head, newHead); // The paper updates tail here, but I'm pretty sure that's wrong.
				continue;
			}
			
			// 3) the node value was by an interfering Combine operation before the AddToBatch 
			// succeeded (the AddToBatch failed).
			if (q.items.get(ticket) == SENTINEL_TWO) { // gaps
				Head newHead = new Head(headIndex + 1, headCount); 
				q.head.compareAndSet(head, newHead); // The paper updates tail here, but I'm pretty sure that's wrong.
				continue;
			}
			
			// 2) The node value is a write descriptor which implies that the AddToBatch 
			// operation completed successfully.
			WriteDescriptor<AtomicMarkableReference<T>> writeOp = q.items.get(ticket);
			if (!writeOp.pending) { // update head
				Head newHead = new Head(headIndex + 1, headCount+1);
				q.head.compareAndSet(head, newHead); // The paper updates tail here, but I'm pretty sure that's wrong.
				continue;
			}
			
			// complete writeop's pending operation
			if (writeOp.pending && q.head.get().index == headIndex && q.head.get().count == headCount) {
				int temp = descr.offset + headCount;
				vals.get(getBucket(temp)).compareAndSet(getIdxWithinBucket(temp), oldValue, writeOp.newValue);
			}
			
			Head newHead = new Head(headIndex + 1, headCount+1);
			q.head.compareAndSet(head, newHead);
			writeOp.pending = true;
		}
		
		int newSize = descr.offset + headCount;
		if (descr.opType == OpType.POP) {
			newSize--;
		}
		
		Descriptor<AtomicMarkableReference<T>> newDesc = new Descriptor<AtomicMarkableReference<T>>(newSize, null, null);
		desc.compareAndSet(descr, newDesc);
		
		if (!helper) {
			int index = descr.offset + headCount;
			T elem = readAt(index);
			markNode(index);
			return elem;
		}
		
		return null;
	}

	// New method, not in the paper's spec.
	T peek() {
		Descriptor<AtomicMarkableReference<T>> currDesc = desc.get();
		completeWrite(currDesc.writeOp); // Complete any pending push.
		if (currDesc.size == 0) return null;
		else return readAt(currDesc.size - 1);
	}

	// they modified this method, don't forget to do that
	void writeAt(int idx, T newValue) {
		vals.get(getBucket(idx)).get(getIdxWithinBucket(idx)).set(newValue, false);
	}

	// they modified this method, don't forget to do that
	T readAt(int idx) {
		return vals.get(getBucket(idx)).get(getIdxWithinBucket(idx)).getReference();
	}
	private AtomicMarkableReference<T> readRefAt(int idx) {
		return vals.get(getBucket(idx)).get(getIdxWithinBucket(idx));
	}
	
	int size() {
		int size = desc.get().size;
		if (desc.get().writeOp.pending) { // A pending pushBack().
			size--;
		}
		return size;
	}
	
	// how to do this in java? AtomicMarkableReference<Integer>? look at LockFreeList
	private void markNode(int idx) {
		int[] temp = new int[1];
		temp[-1] = 0;
	}
	
	// Finish a pending write operation.
	private void completeWrite(WriteDescriptor<AtomicMarkableReference<T>> writeOp) {
		if (writeOp != null && writeOp.pending) {
			// We don't need to loop until it succeeds, because a failure means some other thread
			// completed it for us.
			vals.get(getBucket(writeOp.idx)).compareAndSet(getIdxWithinBucket(writeOp.idx),
					writeOp.newValue, writeOp.oldValue);
			writeOp.pending = false;
		}
	}

	// Create a new bucket.
	private void allocateBucket(int bucketIdx) {
		int bucketSize = 1 << (bucketIdx + highestBit(FBS));
		AtomicReferenceArray<AtomicMarkableReference<T>> newBucket = new AtomicReferenceArray<>(bucketSize);
		if (!vals.compareAndSet(bucketIdx, null, newBucket)) {
			// Do nothing, and let the GC free newBucket. (Another thread allocated the bucket or 
			// it already existed.)
		}
	}

	// Returns the index of the bucket for i (level zero of the array).
	private int getBucket(int i) {
		int pos = i + FBS;
		int hiBit = highestBit(pos);
		return hiBit - highestBit(FBS);
	}
	// Returns the index within the bucket for i (level one of the array).
	private int getIdxWithinBucket(int i) {
		int pos = i + FBS;
		int hiBit = highestBit(pos);
		return pos ^ (1 << hiBit);
	}

	// Returns the index of the highest one bit.
	private int highestBit(int n) {
		return Integer.numberOfTrailingZeros(Integer.highestOneBit(n));
	}

	private static class Descriptor<E> {
		int size, offset;
		WriteDescriptor<E> writeOp;
		Queue<E> batch;
		OpType opType;

		Descriptor(int _size, WriteDescriptor<E> _writeOp, OpType _opType) {
			size = _size;
			offset = 0;
			writeOp = _writeOp;
			opType = _opType;
			batch = null;
		}
	}

	private static class WriteDescriptor<E> {
		E oldValue, newValue;
		int idx;
		boolean pending;
		Queue<E> batch;

		WriteDescriptor(E _oldV, E _newV, int _idx) {
			oldValue = _oldV;
			newValue = _newV;
			pending = true;
			idx = _idx;
			batch = null;
		}
	}

	private static class Queue<E> {
		boolean closed;
		AtomicReferenceArray<WriteDescriptor<E>> items;
		AtomicInteger tail;
		AtomicReference<Head> head;
		
		Queue() {
			items = new AtomicReferenceArray<>(QSize);
			closed = false;
			tail = new AtomicInteger(1);
			head = new AtomicReference<Head>(new Head(0, 0));
		}
		
		Queue(WriteDescriptor<E> firstElement) {
			this();
			items.set(0, firstElement);
		}
	}
	
	private static class Head {
		// count indicates the number of successfully combined operations.
		int index, count;
		Head(int _index, int _count) {
			index = _index;
			count = _count;
		}
	}

	private static enum OpType {
		PUSH, POP;
	}

	private static class ThreadInfo<T> {
		// vector??
		Queue<AtomicMarkableReference<T>> q, batch;
		int offset;
		
		public ThreadInfo() {
			q = new Queue<AtomicMarkableReference<T>>();
			batch = new Queue<AtomicMarkableReference<T>>();
			offset = 0;
		}
	}
}

