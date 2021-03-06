/*
 * Copyright (c) 2014-2018 by The Monix Project Developers.
 * See the project homepage at: https://monix.io
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package monix.tail
package internal

import cats.effect.Sync
import cats.syntax.all._
import monix.execution.misc.NonFatal
import monix.tail.Iterant.{Halt, Last, Next, NextBatch, NextCursor, Suspend}
import monix.tail.batches.BatchCursor

import scala.runtime.ObjectRef

private[tail] object IterantFoldWhileLeft {
  /** Implementation for `Iterant.foldWhileLeftL`. */
  def strict[F[_], A, S](self: Iterant[F, A], seed: => S, f: (S, A) => Either[S, S])
    (implicit F: Sync[F]): F[S] = {

    def process(stopRef: ObjectRef[F[Unit]])(state: S, cursor: BatchCursor[A], rest: F[Iterant[F, A]], stop: F[Unit]) = {
      var hasResult = false
      var s = state

      while (!hasResult && cursor.hasNext()) {
        f(s, cursor.next()) match {
          case Left(s2) => s = s2
          case Right(s2) =>
            hasResult = true
            s = s2
        }
      }

      if (hasResult)
        stop.map(_ => s)
      else
        rest.flatMap(loop(stopRef, s))
    }

    def loop(stopRef: ObjectRef[F[Unit]], state: S)(self: Iterant[F, A]): F[S] = {
      try self match {
        case Next(a, rest, stop) =>
          stopRef.elem = stop
          f(state, a) match {
            case Left(s) => rest.flatMap(loop(stopRef, s))
            case Right(s) => stop.map(_ => s)
          }

        case NextCursor(cursor, rest, stop) =>
          stopRef.elem = stop
          process(stopRef)(state, cursor, rest, stop)

        case NextBatch(batch, rest, stop) =>
          stopRef.elem = stop
          val cursor = batch.cursor()
          process(stopRef)(state, cursor, rest, stop)

        case Suspend(rest, stop) =>
          stopRef.elem = stop
          rest.flatMap(loop(stopRef, state))

        case Last(a) =>
          stopRef.elem = null.asInstanceOf[F[Unit]]
          F.pure(f(state, a) match {
            case Left(s) => s
            case Right(s) => s
          })

        case Halt(optE) =>
          optE match {
            case None =>
              F.pure(state)
            case Some(e) =>
              stopRef.elem = null.asInstanceOf[F[Unit]]
              F.raiseError(e)
          }
      }
      catch {
        case e if NonFatal(e) =>
          F.raiseError(e)
      }
    }

    F.suspend {
      // Reference to keep track of latest `earlyStop` value
      val stopRef = ObjectRef.create(null.asInstanceOf[F[Unit]])
      // Catch-all exceptions, ensuring latest `earlyStop` gets called
      F.handleErrorWith(loop(stopRef, seed)(self)) { ex =>
        stopRef.elem match {
          case null => F.raiseError(ex)
          case stop => stop *> F.raiseError(ex)
        }
      }
    }
  }

  /** Implementation for `Iterant.foldWhileLeftEvalL`. */
  def eval[F[_], A, S](self: Iterant[F, A], seed: F[S], f: (S, A) => F[Either[S, S]])
    (implicit F: Sync[F]): F[S] = {

    def process(stopRef: ObjectRef[F[Unit]])(state: S, stop: F[Unit], rest: F[Iterant[F, A]], a: A): F[S] = {
      val fs = f(state, a)

      fs.flatMap {
        case Left(s) => rest.flatMap(loop(stopRef, s))
        case Right(s) => stop.map(_ => s)
      }
    }

    def loop(stopRef: ObjectRef[F[Unit]], state: S)(self: Iterant[F, A]): F[S] = {
      try self match {
        case Next(a, rest, stop) =>
          stopRef.elem = stop
          process(stopRef)(state, stop, rest, a)

        case NextCursor(cursor, rest, stop) =>
          stopRef.elem = stop
          if (!cursor.hasNext()) rest.flatMap(loop(stopRef, state)) else {
            val a = cursor.next()
            process(stopRef)(state, stop, F.pure(self), a)
          }

        case NextBatch(batch, rest, stop) =>
          stopRef.elem = stop
          val cursor = batch.cursor()
          if (!cursor.hasNext()) rest.flatMap(loop(stopRef, state)) else {
            val a = cursor.next()
            process(stopRef)(state, stop, F.pure(NextCursor(cursor, rest, stop)), a)
          }

        case Suspend(rest, stop) =>
          stopRef.elem = stop
          rest.flatMap(loop(stopRef, state))

        case Last(a) =>
          stopRef.elem = null.asInstanceOf[F[Unit]]
          f(state, a).map {
            case Left(s) => s
            case Right(s) => s
          }

        case Halt(optE) =>
          optE match {
            case None =>
              F.pure(state)
            case Some(e) =>
              stopRef.elem = null.asInstanceOf[F[Unit]]
              F.raiseError(e)
          }
      }
      catch {
        case e if NonFatal(e) =>
          F.raiseError(e)
      }
    }

    F.suspend {
      // Reference to keep track of latest `earlyStop` value
      val stopRef = ObjectRef.create(null.asInstanceOf[F[Unit]])
      // Catch-all exceptions, ensuring latest `earlyStop` gets called
      F.handleErrorWith(seed.flatMap(s => loop(stopRef, s)(self))) { ex =>
        stopRef.elem match {
          case null => F.raiseError(ex)
          case stop => stop *> F.raiseError(ex)
        }
      }
    }
  }
}