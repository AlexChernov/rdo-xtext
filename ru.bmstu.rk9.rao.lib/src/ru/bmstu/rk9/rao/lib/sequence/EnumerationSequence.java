package ru.bmstu.rk9.rao.lib.sequence;

import java.util.Iterator;
import java.util.List;

public class EnumerationSequence<T> implements ArbitraryTypeSequence<T> {
	private final Iterator<T> iterator;

	public EnumerationSequence(List<T> enumerationList) {
		this.iterator = new ListGenerator<T>(enumerationList).iterator();
	}

	@Override
	public T next() {
		return iterator.next();
	}
}
